"""Private ALOUTE moderation adapter backed by OpenAI's Moderation API.

This service is intended to run only on the private Docker Compose network.
It never treats upstream failures as an approval.
"""
from __future__ import annotations

import base64
import hmac
import io
import json
import logging
import math
import os
import subprocess
import tempfile
import time
import warnings
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from PIL import Image, ImageOps, UnidentifiedImageError
from pydantic import BaseModel, Field

LOG = logging.getLogger("aloute.moderation")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
SERVICE_TOKEN = os.getenv("MODERATION_API_TOKEN", "").strip()
MODEL = os.getenv("OPENAI_MODERATION_MODEL", "omni-moderation-latest").strip()
ALLOWED_MEDIA_HOSTS = {
    host.strip().lower().rstrip(".")
    for host in os.getenv("MODERATION_ALLOWED_MEDIA_HOSTS", "res.cloudinary.com").split(",")
    if host.strip()
}
UPSTREAM_TIMEOUT = max(3.0, min(float(os.getenv("OPENAI_MODERATION_TIMEOUT_SECONDS", "8")), 20.0))
AUDIO_TIMEOUT = max(5.0, min(float(os.getenv("OPENAI_AUDIO_TIMEOUT_SECONDS", "15")), 20.0))
AUDIO_MODEL = os.getenv("OPENAI_AUDIO_MODEL", "gpt-4o-mini-transcribe").strip()
MAX_TEXT_CHARS = 20_000
MAX_IMAGE_BYTES = 20 * 1024 * 1024
MAX_VIDEO_BYTES = 50 * 1024 * 1024
MAX_VIDEO_SECONDS = 180
MAX_DOWNLOAD_SECONDS = 8
VIDEO_FRAME_FRACTIONS = (0.10, 0.50, 0.90)

if not OPENAI_API_KEY:
    raise RuntimeError("OPENAI_API_KEY is required by the moderation service")
if len(SERVICE_TOKEN.encode("utf-8")) < 32:
    raise RuntimeError("MODERATION_API_TOKEN must be a unique secret of at least 32 bytes")
if not ALLOWED_MEDIA_HOSTS:
    raise RuntimeError("MODERATION_ALLOWED_MEDIA_HOSTS must contain at least one exact hostname")

# Keep decompression-bomb protection enabled; oversized images must fail closed.
Image.MAX_IMAGE_PIXELS = 30_000_000
warnings.simplefilter("error", Image.DecompressionBombWarning)
UPSTREAM = httpx.Client(
    timeout=httpx.Timeout(UPSTREAM_TIMEOUT, connect=min(3.0, UPSTREAM_TIMEOUT)),
    headers={"Authorization": f"Bearer {OPENAI_API_KEY}", "Content-Type": "application/json"},
)
AUDIO_UPSTREAM = httpx.Client(
    timeout=httpx.Timeout(AUDIO_TIMEOUT, connect=min(3.0, AUDIO_TIMEOUT)),
    headers={"Authorization": f"Bearer {OPENAI_API_KEY}"},
)

app = FastAPI(title="ALOUTE Moderation Service", docs_url=None, redoc_url=None, openapi_url=None)


class TextRequest(BaseModel):
    text: str = Field(min_length=1, max_length=MAX_TEXT_CHARS)


class MediaRequest(BaseModel):
    url: str = Field(min_length=8, max_length=4096)
    mediaType: str = Field(pattern=r"^(image|video)$")


def require_service_token(x_moderation_token: str | None = Header(default=None)) -> None:
    if not x_moderation_token or not hmac.compare_digest(x_moderation_token, SERVICE_TOKEN):
        raise HTTPException(status_code=401, detail="Unauthorized")


@app.get("/healthz")
def healthz() -> dict[str, str]:
    # Deliberately does not call the upstream API, to avoid consuming requests in health checks.
    if not Path("/usr/bin/ffmpeg").exists() or not Path("/usr/bin/ffprobe").exists():
        raise HTTPException(status_code=503, detail="Media processor unavailable")
    return {"status": "ok", "provider": "moderation-api"}


@app.post("/moderate")
def moderate(request: TextRequest, _: None = Depends(require_service_token)) -> dict[str, Any]:
    return _moderate_input(request.text)


@app.post("/moderate-media")
def moderate_media(request: MediaRequest, _: None = Depends(require_service_token)) -> dict[str, Any]:
    media_url = _validate_media_url(request.url)
    max_bytes = MAX_IMAGE_BYTES if request.mediaType == "image" else MAX_VIDEO_BYTES
    payload = _download_media(media_url, max_bytes)
    transcript: str | None = None
    if request.mediaType == "image":
        frame_data_urls = [_image_data_url(payload)]
    else:
        frame_data_urls, transcript = _video_frames_and_transcript(payload)

    inputs: list[dict[str, Any]] = [
        {"type": "image_url", "image_url": {"url": data_url}}
        for data_url in frame_data_urls
    ]
    if transcript and transcript.strip():
        inputs.insert(0, {"type": "text", "text": "Audio transcript from user-uploaded video:\n" + transcript[:MAX_TEXT_CHARS]})
    return _moderate_input(inputs)


def _validate_media_url(raw_url: str) -> str:
    try:
        parsed = urlsplit(raw_url)
        host = (parsed.hostname or "").lower().rstrip(".")
        port = parsed.port
    except ValueError as exc:
        raise HTTPException(status_code=422, detail="Invalid media URL") from exc

    if (
        parsed.scheme != "https"
        or host not in ALLOWED_MEDIA_HOSTS
        or parsed.username is not None
        or parsed.password is not None
        or port not in (None, 443)
        or not parsed.path
    ):
        # An exact allow-list and no redirects prevent use as a generic SSRF proxy.
        raise HTTPException(status_code=422, detail="Media URL host is not allow-listed")
    return raw_url


def _download_media(url: str, max_bytes: int) -> bytes:
    started = time.monotonic()
    buffer = bytearray()
    try:
        with httpx.Client(
            timeout=httpx.Timeout(5.0, connect=2.0),
            follow_redirects=False,
            headers={"User-Agent": "ALOUTE-Moderation/1.0"},
        ) as client:
            with client.stream("GET", url) as response:
                if response.status_code != 200:
                    LOG.warning("Media host returned HTTP %s", response.status_code)
                    raise HTTPException(status_code=503, detail="Media could not be fetched for moderation")
                length = response.headers.get("content-length")
                if length and length.isdigit() and int(length) > max_bytes:
                    raise HTTPException(status_code=413, detail="Media is too large for moderation")
                for chunk in response.iter_bytes(64 * 1024):
                    buffer.extend(chunk)
                    if len(buffer) > max_bytes:
                        raise HTTPException(status_code=413, detail="Media is too large for moderation")
                    if time.monotonic() - started > MAX_DOWNLOAD_SECONDS:
                        raise HTTPException(status_code=503, detail="Media download timed out")
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        LOG.warning("Media fetch failed (%s)", type(exc).__name__)
        raise HTTPException(status_code=503, detail="Media could not be fetched for moderation") from exc
    if not buffer:
        raise HTTPException(status_code=422, detail="Empty media file")
    return bytes(buffer)


def _image_data_url(image_bytes: bytes) -> str:
    try:
        with Image.open(io.BytesIO(image_bytes)) as source:
            source = ImageOps.exif_transpose(source)
            source.thumbnail((1024, 1024))
            rgb = source.convert("RGB")
            output = io.BytesIO()
            rgb.save(output, format="JPEG", quality=85, optimize=True)
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
        LOG.warning("Image was not a valid/safe raster image (%s)", type(exc).__name__)
        raise HTTPException(status_code=422, detail="Unsupported or invalid image") from exc
    encoded = base64.b64encode(output.getvalue()).decode("ascii")
    return f"data:image/jpeg;base64,{encoded}"


def _video_frames_and_transcript(video_bytes: bytes) -> tuple[list[str], str | None]:
    """Sample three visual frames and transcribe an audio track before moderation.

    The upstream moderation endpoint accepts text/images, not native video/audio. Audio is
    therefore transcribed separately, then evaluated as text with the video frames.
    """
    with tempfile.TemporaryDirectory(prefix="aloute-mod-") as temp_dir:
        root = Path(temp_dir)
        video_path = root / "input-video.bin"
        video_path.write_bytes(video_bytes)
        try:
            probe = subprocess.run(
                [
                    "ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_type",
                    "-of", "json", str(video_path),
                ],
                capture_output=True, text=True, timeout=4, check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise HTTPException(status_code=503, detail="Video inspection timed out") from exc
        if probe.returncode != 0:
            raise HTTPException(status_code=422, detail="Unsupported or invalid video")
        try:
            probe_data = json.loads(probe.stdout)
            duration = float(probe_data["format"]["duration"])
            streams = probe_data.get("streams", [])
            has_audio = any(stream.get("codec_type") == "audio" for stream in streams)
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=422, detail="Video metadata could not be read") from exc
        if not (0.0 < duration <= MAX_VIDEO_SECONDS):
            raise HTTPException(status_code=413, detail="Video duration must be 3 minutes or less")

        data_urls: list[str] = []
        timestamps: list[float] = []
        for fraction in VIDEO_FRAME_FRACTIONS:
            timestamp = min(max(0.0, duration * fraction), max(0.0, duration - 0.05))
            if not any(abs(timestamp - previous) < 0.05 for previous in timestamps):
                timestamps.append(timestamp)

        for index, timestamp in enumerate(timestamps):
            frame_path = root / f"frame-{index}.jpg"
            try:
                result = subprocess.run(
                    [
                        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                        "-ss", f"{timestamp:.3f}", "-i", str(video_path),
                        "-frames:v", "1", "-vf", "scale='min(768,iw)':-2",
                        "-q:v", "5", "-y", str(frame_path),
                    ],
                    capture_output=True, text=True, timeout=4, check=False,
                )
            except subprocess.TimeoutExpired as exc:
                raise HTTPException(status_code=503, detail="Video frame extraction timed out") from exc
            if result.returncode != 0 or not frame_path.exists() or frame_path.stat().st_size == 0:
                LOG.warning("Could not sample video frame at %.2f seconds", timestamp)
                raise HTTPException(status_code=503, detail="Video could not be fully checked")
            data_urls.append(_image_data_url(frame_path.read_bytes()))
        if not data_urls:
            raise HTTPException(status_code=503, detail="Video contains no checkable frames")

        transcript: str | None = None
        if has_audio:
            audio_path = root / "audio.mp3"
            try:
                audio_result = subprocess.run(
                    [
                        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                        "-i", str(video_path), "-map", "0:a:0", "-vn", "-ac", "1",
                        "-ar", "16000", "-b:a", "48k", "-y", str(audio_path),
                    ],
                    capture_output=True, text=True, timeout=5, check=False,
                )
            except subprocess.TimeoutExpired as exc:
                raise HTTPException(status_code=503, detail="Video audio extraction timed out") from exc
            if audio_result.returncode != 0 or not audio_path.exists() or audio_path.stat().st_size == 0:
                raise HTTPException(status_code=503, detail="Video audio could not be checked")
            if audio_path.stat().st_size > 25 * 1024 * 1024:
                raise HTTPException(status_code=413, detail="Video audio is too large for transcription")
            try:
                with audio_path.open("rb") as audio_file:
                    response = AUDIO_UPSTREAM.post(
                        "https://api.openai.com/v1/audio/transcriptions",
                        data={"model": AUDIO_MODEL, "response_format": "json"},
                        files={"file": ("video-audio.mp3", audio_file, "audio/mpeg")},
                    )
                response.raise_for_status()
                transcript_data = response.json()
                transcript = transcript_data.get("text") if isinstance(transcript_data, dict) else None
                if not isinstance(transcript, str):
                    raise ValueError("Invalid transcription response")
            except (httpx.HTTPError, json.JSONDecodeError, ValueError) as exc:
                status = getattr(getattr(exc, "response", None), "status_code", None)
                LOG.warning("Video audio transcription unavailable (status=%s, error=%s)", status, type(exc).__name__)
                raise HTTPException(status_code=503, detail="Video audio transcription unavailable") from exc
        return data_urls, transcript


def _moderate_input(value: str | list[dict[str, Any]]) -> dict[str, Any]:
    try:
        response = UPSTREAM.post(
            "https://api.openai.com/v1/moderations",
            json={"model": MODEL, "input": value},
        )
        response.raise_for_status()
        payload = response.json()
    except (httpx.HTTPError, json.JSONDecodeError, ValueError) as exc:
        status = getattr(getattr(exc, "response", None), "status_code", None)
        LOG.warning("Upstream moderation unavailable (status=%s, error=%s)", status, type(exc).__name__)
        raise HTTPException(status_code=503, detail="Content moderation provider unavailable") from exc

    results = payload.get("results") if isinstance(payload, dict) else None
    if not isinstance(results, list) or not results:
        LOG.warning("Upstream moderation response had no results")
        raise HTTPException(status_code=503, detail="Invalid moderation provider response")

    flagged = False
    max_score = 0.0
    max_label = "safe"
    for result in results:
        if (
            not isinstance(result, dict)
            or not isinstance(result.get("category_scores"), dict)
            or not isinstance(result.get("flagged"), bool)
            or not result["category_scores"]
        ):
            raise HTTPException(status_code=503, detail="Invalid moderation provider response")
        flagged = flagged or result["flagged"]
        for category, raw_score in result["category_scores"].items():
            if (
                isinstance(raw_score, bool)
                or not isinstance(raw_score, (int, float))
                or not math.isfinite(float(raw_score))
                or not 0.0 <= float(raw_score) <= 1.0
            ):
                raise HTTPException(status_code=503, detail="Invalid moderation provider score")
            if float(raw_score) > max_score:
                max_score = float(raw_score)
                max_label = str(category)

    # The Java application uses <0.5=APPROVED, >=0.5=FLAGGED, >=0.8=HIDDEN.
    # Honor the provider's aggregate flagged decision while keeping its score useful
    # for distinguishing flagged vs strongly flagged content.
    if flagged:
        return {"label": max_label if max_label != "safe" else "flagged", "score": round(max(0.5, max_score), 6)}
    return {"label": "safe", "score": round(min(max_score, 0.499999), 6)}
