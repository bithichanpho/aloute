"""Private ALOUTE moderation adapter backed by OpenAI's Moderation API.

This service is intended to run only on the private Docker Compose network.
It never treats upstream failures as an approval.

Reliability model
-----------------
* Requests have bounded waits; background jobs use cooperative deadlines and bounded I/O.
* Transient upstream / media-host failures are retried inside that budget and then reported
  as HTTP 503 (the Java client treats 408/425/429/5xx as retryable).
* 4xx is returned ONLY when the input itself is unusable (corrupt image/video, disallowed
  host, over the size limit). Anything uncertain is 503, never a "permanent" error.
* Media work runs as a background job with single-flight de-duplication and a short result
  cache, so a client timeout + retry reuses the finished (or in-flight) result instead of
  piling up more ffmpeg / download work.
"""
from __future__ import annotations

import base64
import hmac
import io
import json
import logging
import math
import os
import re
import subprocess
import tempfile
import threading
import time
import unicodedata
import warnings
from collections import OrderedDict
from concurrent.futures import Future, ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeout
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urljoin, urlsplit

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from PIL import Image, ImageFile, ImageOps, UnidentifiedImageError
from pydantic import BaseModel, Field

LOG = logging.getLogger("aloute.moderation")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))


def _env_float(name: str, default: float, low: float, high: float) -> float:
    try:
        value = float(os.getenv(name, str(default)))
    except ValueError:
        value = default
    return max(low, min(value, high))


def _env_int(name: str, default: int, low: int, high: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except ValueError:
        value = default
    return max(low, min(value, high))


OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
SERVICE_TOKEN = os.getenv("MODERATION_API_TOKEN", "").strip()
MODEL = os.getenv("OPENAI_MODERATION_MODEL", "omni-moderation-latest").strip()
AUDIO_MODEL = os.getenv("OPENAI_AUDIO_MODEL", "gpt-4o-mini-transcribe").strip()
ALLOWED_MEDIA_HOSTS = {
    host.strip().lower().rstrip(".")
    for host in os.getenv("MODERATION_ALLOWED_MEDIA_HOSTS", "res.cloudinary.com").split(",")
    if host.strip()
}

# Per-call upstream caps (each call is additionally bounded by the request's remaining budget).
UPSTREAM_TIMEOUT = _env_float("OPENAI_MODERATION_TIMEOUT_SECONDS", 8, 3, 20)
AUDIO_TIMEOUT = _env_float("OPENAI_AUDIO_TIMEOUT_SECONDS", 15, 5, 30)
UPSTREAM_MAX_ATTEMPTS = _env_int("MODERATION_UPSTREAM_MAX_ATTEMPTS", 3, 1, 5)
DOWNLOAD_MAX_ATTEMPTS = _env_int("MODERATION_DOWNLOAD_MAX_ATTEMPTS", 3, 1, 5)

# Budgets. They must stay below the Java timeouts (text 10s, upload media 12-15s, post media 60s).
TEXT_BUDGET_SECONDS = _env_float("MODERATION_TEXT_BUDGET_SECONDS", 9, 3, 30)
IMAGE_WAIT_SECONDS = _env_float("MODERATION_IMAGE_WAIT_SECONDS", 11, 3, 60)
VIDEO_WAIT_SECONDS = _env_float("MODERATION_VIDEO_WAIT_SECONDS", 50, 5, 120)
# Background jobs may outlive the caller so that a retry finds the finished result in the cache.
IMAGE_JOB_BUDGET_SECONDS = _env_float("MODERATION_IMAGE_JOB_BUDGET_SECONDS", 30, 10, 120)
VIDEO_JOB_BUDGET_SECONDS = _env_float("MODERATION_VIDEO_JOB_BUDGET_SECONDS", 75, 20, 240)
IMAGE_DOWNLOAD_SECONDS = _env_float("MODERATION_IMAGE_DOWNLOAD_SECONDS", 15, 3, 60)
VIDEO_DOWNLOAD_SECONDS = _env_float("MODERATION_VIDEO_DOWNLOAD_SECONDS", 30, 5, 120)

MAX_TEXT_CHARS = 20_000
MAX_IMAGE_BYTES = 20 * 1024 * 1024
MAX_VIDEO_BYTES = 50 * 1024 * 1024
MAX_VIDEO_SECONDS = 180
VIDEO_FRAME_FRACTIONS = (0.10, 0.50, 0.90)
PROBE_TIMEOUT = 10.0
FRAME_TIMEOUT = 10.0
AUDIO_EXTRACT_TIMEOUT = 15.0

MEDIA_WORKERS = _env_int("MODERATION_MEDIA_WORKERS", 4, 1, 16)
MAX_PENDING_MEDIA_JOBS = _env_int("MODERATION_MAX_PENDING_MEDIA_JOBS", 24, 2, 200)
CACHE_TTL_SECONDS = _env_float("MODERATION_RESULT_CACHE_SECONDS", 300, 0, 3600)
CACHE_MAX_ENTRIES = 512

if not OPENAI_API_KEY:
    raise RuntimeError("OPENAI_API_KEY is required by the moderation service")
if len(SERVICE_TOKEN.encode("utf-8")) < 32:
    raise RuntimeError("MODERATION_API_TOKEN must be a unique secret of at least 32 bytes")
if not ALLOWED_MEDIA_HOSTS:
    raise RuntimeError("MODERATION_ALLOWED_MEDIA_HOSTS must contain at least one exact hostname")

# Preserve the default pixel cap; JPEG draft decoding reduces work before resizing.
Image.MAX_IMAGE_PIXELS = _env_int("MODERATION_MAX_IMAGE_PIXELS", 30_000_000, 10_000_000, 100_000_000)
ImageFile.LOAD_TRUNCATED_IMAGES = False  # preserve fail-closed decoding of incomplete uploads
warnings.simplefilter("error", Image.DecompressionBombWarning)

UPSTREAM = httpx.Client(
    timeout=httpx.Timeout(UPSTREAM_TIMEOUT, connect=min(3.0, UPSTREAM_TIMEOUT)),
    headers={"Authorization": f"Bearer {OPENAI_API_KEY}", "Content-Type": "application/json"},
    transport=httpx.HTTPTransport(retries=1),
)
AUDIO_UPSTREAM = httpx.Client(
    timeout=httpx.Timeout(AUDIO_TIMEOUT, connect=min(3.0, AUDIO_TIMEOUT)),
    headers={"Authorization": f"Bearer {OPENAI_API_KEY}"},
    transport=httpx.HTTPTransport(retries=1),
)
# One pooled client for media downloads. Redirects are followed manually and re-validated
# against the allow-list, so the service can never be used as a generic SSRF proxy.
DOWNLOADER = httpx.Client(
    follow_redirects=False,
    headers={"User-Agent": "ALOUTE-Moderation/1.0"},
    transport=httpx.HTTPTransport(
        retries=1,
        limits=httpx.Limits(max_connections=16, max_keepalive_connections=8, keepalive_expiry=15.0),
    ),
)

_JOB_POOL = ThreadPoolExecutor(max_workers=MEDIA_WORKERS, thread_name_prefix="mod-job")
_WORK_POOL = ThreadPoolExecutor(max_workers=MEDIA_WORKERS * 3, thread_name_prefix="mod-work")

app = FastAPI(title="ALOUTE Moderation Service", docs_url=None, redoc_url=None, openapi_url=None)


@app.exception_handler(Exception)
async def _unexpected_error(_: Request, exc: Exception) -> JSONResponse:
    # An unexpected bug must look transient (retryable), never like a permanent rejection.
    LOG.exception("Unhandled moderation error (%s)", type(exc).__name__)
    return JSONResponse(status_code=503, content={"detail": "Moderation temporarily unavailable"})


class TextRequest(BaseModel):
    text: str = Field(min_length=1, max_length=MAX_TEXT_CHARS)


class MediaRequest(BaseModel):
    url: str = Field(min_length=8, max_length=4096)
    mediaType: str = Field(pattern=r"^(image|video)$")


def require_service_token(x_moderation_token: str | None = Header(default=None)) -> None:
    if not x_moderation_token or not hmac.compare_digest(x_moderation_token, SERVICE_TOKEN):
        raise HTTPException(status_code=401, detail="Unauthorized")


# --------------------------------------------------------------------------------------
# Deadlines and upstream state
# --------------------------------------------------------------------------------------
class Deadline:
    """A monotonic time budget shared by every step of one request/job."""

    def __init__(self, seconds: float) -> None:
        self._end = time.monotonic() + seconds

    def remaining(self) -> float:
        return self._end - time.monotonic()

    def timeout(self, cap: float, floor: float = 0.5) -> float:
        remaining = self.remaining()
        if remaining <= floor:
            raise HTTPException(status_code=503, detail="Moderation time budget exhausted")
        return min(cap, remaining)


_STATE_LOCK = threading.Lock()
_UPSTREAM_STATE: dict[str, Any] = {"last_ok": None, "consecutive_failures": 0, "last_status": None}


def _record_upstream(ok: bool, status: int | None) -> None:
    with _STATE_LOCK:
        _UPSTREAM_STATE["last_status"] = status
        if ok:
            _UPSTREAM_STATE["last_ok"] = time.time()
            _UPSTREAM_STATE["consecutive_failures"] = 0
        else:
            _UPSTREAM_STATE["consecutive_failures"] += 1


def _media_tools_present() -> bool:
    return Path("/usr/bin/ffmpeg").exists() and Path("/usr/bin/ffprobe").exists()


@app.get("/healthz")
async def healthz() -> dict[str, Any]:
    # async on purpose: it runs on the event loop and is never starved by busy worker threads.
    # It does not call the upstream API (no quota use); upstream health is only reported.
    if not _media_tools_present():
        raise HTTPException(status_code=503, detail="Media processor unavailable")
    with _STATE_LOCK:
        failures = int(_UPSTREAM_STATE["consecutive_failures"])
        last_status = _UPSTREAM_STATE["last_status"]
    with _FLIGHT_LOCK:
        pending = len(_INFLIGHT)
    return {
        "status": "ok",
        "provider": "moderation-api",
        "pendingMediaJobs": pending,
        "upstreamConsecutiveFailures": failures,
        "upstreamLastStatus": last_status,
    }


# --------------------------------------------------------------------------------------
# Upstream calls: bounded retries with accurate error classification
# --------------------------------------------------------------------------------------
class _UpstreamRejected(Exception):
    """The provider refused this specific payload (HTTP 400/413/422); retrying cannot help."""

    def __init__(self, status: int) -> None:
        super().__init__(f"upstream rejected payload with HTTP {status}")
        self.status = status


def _retry_after_seconds(response: Any) -> float | None:
    try:
        raw = response.headers.get("retry-after")
        return max(0.0, float(raw)) if raw is not None else None
    except (AttributeError, TypeError, ValueError):
        return None


def _is_quota_exhausted(response: Any) -> bool:
    try:
        error = response.json().get("error") or {}
        return error.get("code") == "insufficient_quota"
    except Exception:  # noqa: BLE001 - diagnostic only
        return False


def _call_upstream(call: Callable[[float], Any], deadline: Deadline, cap: float, what: str) -> Any:
    """Run an upstream HTTP call with retries inside the deadline.

    Returns the successful response. Raises _UpstreamRejected for payload-specific 4xx and
    HTTPException(503) for everything transient or configuration related.
    """
    last = "no attempt"
    retry_after = None
    for attempt in range(UPSTREAM_MAX_ATTEMPTS):
        if attempt:
            delay = min(max(0.5 * (2 ** (attempt - 1)), retry_after or 0.0), 4.0)
            if deadline.remaining() <= delay + 1.0:
                break
            time.sleep(delay)
        timeout = deadline.timeout(cap)
        try:
            response = call(timeout)
        except httpx.HTTPError as exc:  # timeouts, connection resets, DNS, ...
            last = type(exc).__name__
            retry_after = None
            _record_upstream(False, None)
            LOG.warning("%s call failed (attempt %d/%d): %s", what, attempt + 1, UPSTREAM_MAX_ATTEMPTS, last)
            continue
        status = int(getattr(response, "status_code", 200))
        deadline.timeout(cap)  # an answer arriving after the budget cannot approve content
        if 200 <= status < 300:
            _record_upstream(True, status)
            return response
        _record_upstream(False, status)
        if status in (401, 403, 404):
            LOG.error("%s rejected our credentials/model (HTTP %s). Check OPENAI_API_KEY and model names.", what, status)
            raise HTTPException(status_code=503, detail="Content moderation provider is not configured correctly")
        if status == 429 and _is_quota_exhausted(response):
            LOG.error("%s failed: OpenAI quota exhausted (HTTP 429 insufficient_quota). Add billing/credits.", what)
            raise HTTPException(status_code=503, detail="Content moderation provider quota exhausted")
        if status in (400, 413, 422):
            raise _UpstreamRejected(status)
        last = f"HTTP {status}"
        retry_after = _retry_after_seconds(response)
        LOG.warning("%s call failed (attempt %d/%d): %s", what, attempt + 1, UPSTREAM_MAX_ATTEMPTS, last)
    LOG.warning("%s unavailable after retries (%s)", what, last)
    raise HTTPException(status_code=503, detail="Content moderation provider unavailable")




# --------------------------------------------------------------------------------------
# Routes
# --------------------------------------------------------------------------------------
@app.post("/moderate")
def moderate(request: TextRequest, _: None = Depends(require_service_token)) -> dict[str, Any]:
    return _moderate_input(request.text, Deadline(TEXT_BUDGET_SECONDS))


@app.post("/moderate-media")
def moderate_media(request: MediaRequest, _: None = Depends(require_service_token)) -> dict[str, Any]:
    media_url = _validate_media_url(request.url)
    key = (media_url, request.mediaType)
    cached = _cache_get(key)
    if cached is not None:
        return cached
    future = _submit_media_job(key)
    wait = IMAGE_WAIT_SECONDS if request.mediaType == "image" else VIDEO_WAIT_SECONDS
    try:
        return future.result(timeout=wait)
    except FutureTimeout as exc:
        # The job keeps running and caches its result; the caller's retry will pick it up.
        raise HTTPException(
            status_code=503,
            detail="Media moderation is still in progress; retry shortly",
            headers={"Retry-After": "5"},
        ) from exc


# --------------------------------------------------------------------------------------
# Single-flight jobs + short result cache
# --------------------------------------------------------------------------------------
_FLIGHT_LOCK = threading.RLock()
_INFLIGHT: dict[tuple[str, str], Future] = {}
_CACHE: OrderedDict[tuple[str, str], tuple[float, dict[str, Any]]] = OrderedDict()


def _cache_get(key: tuple[str, str]) -> dict[str, Any] | None:
    with _FLIGHT_LOCK:
        item = _CACHE.get(key)
        if item is None:
            return None
        if item[0] <= time.monotonic():
            del _CACHE[key]
            return None
        _CACHE.move_to_end(key)
        return dict(item[1])


def _cache_put(key: tuple[str, str], value: dict[str, Any]) -> None:
    if CACHE_TTL_SECONDS <= 0:
        return
    with _FLIGHT_LOCK:
        _CACHE[key] = (time.monotonic() + CACHE_TTL_SECONDS, dict(value))
        _CACHE.move_to_end(key)
        while len(_CACHE) > CACHE_MAX_ENTRIES:
            _CACHE.popitem(last=False)


def _forget_inflight(key: tuple[str, str]) -> None:
    with _FLIGHT_LOCK:
        _INFLIGHT.pop(key, None)


def _submit_media_job(key: tuple[str, str]) -> Future:
    with _FLIGHT_LOCK:
        existing = _INFLIGHT.get(key)
        if existing is not None:
            return existing
        if len(_INFLIGHT) >= MAX_PENDING_MEDIA_JOBS:
            raise HTTPException(
                status_code=503,
                detail="Moderation service is busy; retry shortly",
                headers={"Retry-After": "5"},
            )
        future = _JOB_POOL.submit(_media_job, key)
        _INFLIGHT[key] = future
        future.add_done_callback(lambda _f, k=key: _forget_inflight(k))
        return future


def _media_job(key: tuple[str, str]) -> dict[str, Any]:
    url, media_type = key
    result = _run_media_moderation(url, media_type)
    _cache_put(key, result)  # only successful verdicts are cached; failures are never cached
    return result


def _run_media_moderation(url: str, media_type: str) -> dict[str, Any]:
    is_image = media_type == "image"
    deadline = Deadline(IMAGE_JOB_BUDGET_SECONDS if is_image else VIDEO_JOB_BUDGET_SECONDS)
    payload = _download_media(
        url,
        MAX_IMAGE_BYTES if is_image else MAX_VIDEO_BYTES,
        deadline,
        IMAGE_DOWNLOAD_SECONDS if is_image else VIDEO_DOWNLOAD_SECONDS,
    )
    transcript: str | None = None
    if is_image:
        frame_data_urls = [_image_data_url(payload)]
    else:
        frame_data_urls, transcript = _video_frames_and_transcript(payload, deadline)

    inputs: list[dict[str, Any]] = [
        {"type": "image_url", "image_url": {"url": data_url}} for data_url in frame_data_urls
    ]
    if transcript and transcript.strip():
        inputs.insert(0, {"type": "text", "text": "Audio transcript from user-uploaded video:\n" + transcript[:MAX_TEXT_CHARS]})
    return _moderate_input(inputs, deadline)


# --------------------------------------------------------------------------------------
# Media download
# --------------------------------------------------------------------------------------
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
        # An exact allow-list prevents use as a generic SSRF proxy.
        raise HTTPException(status_code=422, detail="Media URL host is not allow-listed")
    return raw_url


class _TransientDownload(Exception):
    """Fetch failed in a way that is worth retrying (host hiccup, not-yet-available file)."""


def _download_media(url: str, max_bytes: int, deadline: Deadline, total_seconds: float) -> bytes:
    last = "unknown"
    for attempt in range(DOWNLOAD_MAX_ATTEMPTS):
        if attempt:
            delay = 0.5 * attempt
            if deadline.remaining() < delay + 2.0:
                break
            time.sleep(delay)
        try:
            return _download_once(url, max_bytes, deadline, total_seconds)
        except _TransientDownload as exc:
            last = str(exc)
            LOG.warning("Media fetch attempt %d/%d failed: %s", attempt + 1, DOWNLOAD_MAX_ATTEMPTS, last)
    LOG.warning("Media could not be fetched after retries (%s)", last)
    raise HTTPException(status_code=503, detail="Media could not be fetched for moderation")


def _download_once(url: str, max_bytes: int, deadline: Deadline, total_seconds: float) -> bytes:
    current = url
    for _hop in range(4):
        limit = deadline.timeout(total_seconds)
        started = time.monotonic()
        buffer = bytearray()
        try:
            timeout = httpx.Timeout(connect=3.0, read=min(10.0, limit), write=5.0, pool=5.0)
            with DOWNLOADER.stream("GET", current, timeout=timeout) as response:
                status = response.status_code
                if status in (301, 302, 303, 307, 308):
                    location = response.headers.get("location")
                    if not location:
                        raise _TransientDownload("redirect without Location")
                    current = _validate_media_url(urljoin(current, location))
                    continue
                if status != 200:
                    # 404/423/5xx right after upload is usually the CDN not being ready yet.
                    raise _TransientDownload(f"media host returned HTTP {status}")
                length = response.headers.get("content-length")
                if length and length.isdigit() and int(length) > max_bytes:
                    raise HTTPException(status_code=413, detail="Media is too large for moderation")
                for chunk in response.iter_bytes(64 * 1024):
                    buffer.extend(chunk)
                    if len(buffer) > max_bytes:
                        raise HTTPException(status_code=413, detail="Media is too large for moderation")
                    if time.monotonic() - started > limit:
                        raise _TransientDownload("download timed out")
        except httpx.HTTPError as exc:
            raise _TransientDownload(type(exc).__name__) from exc
        if not buffer:
            raise _TransientDownload("empty response body")
        return bytes(buffer)
    raise _TransientDownload("too many redirects")


# --------------------------------------------------------------------------------------
# Images
# --------------------------------------------------------------------------------------
def _flatten_to_rgb(image: Image.Image) -> Image.Image:
    if image.mode in ("RGBA", "LA") or (image.mode == "P" and "transparency" in image.info):
        rgba = image.convert("RGBA")
        background = Image.new("RGB", rgba.size, (255, 255, 255))
        background.paste(rgba, mask=rgba.getchannel("A"))
        return background
    return image.convert("RGB")


def _image_data_url(image_bytes: bytes) -> str:
    try:
        with Image.open(io.BytesIO(image_bytes)) as source:
            if source.format == "JPEG":
                source.draft("RGB", (2048, 2048))  # decode at reduced size: fast, low memory
            oriented = ImageOps.exif_transpose(source)
            oriented.thumbnail((1024, 1024))
            rgb = _flatten_to_rgb(oriented)
            output = io.BytesIO()
            rgb.save(output, format="JPEG", quality=85, optimize=True)
    except (Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
        LOG.warning("Image exceeds the pixel limit (%s)", type(exc).__name__)
        raise HTTPException(status_code=413, detail="Image dimensions are too large for moderation") from exc
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError) as exc:
        LOG.warning("Image was not a valid raster image (%s)", type(exc).__name__)
        raise HTTPException(status_code=422, detail="Unsupported or invalid image") from exc
    encoded = base64.b64encode(output.getvalue()).decode("ascii")
    return f"data:image/jpeg;base64,{encoded}"


# --------------------------------------------------------------------------------------
# Video
# --------------------------------------------------------------------------------------
class _StepTimeout(Exception):
    """A subprocess hit its timeout (environmental, therefore retryable)."""


def _run_tool(cmd: list[str], timeout: float) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(cmd, capture_output=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as exc:
        raise _StepTimeout(cmd[0]) from exc


def _positive_float(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number > 0.0 else None


def _probe_video(video_path: Path, deadline: Deadline) -> tuple[float | None, bool]:
    try:
        probe = _run_tool(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_type,duration",
             "-of", "json", str(video_path)],
            deadline.timeout(PROBE_TIMEOUT),
        )
    except _StepTimeout as exc:
        raise HTTPException(status_code=503, detail="Video inspection timed out") from exc
    if probe.returncode != 0:
        raise HTTPException(status_code=422, detail="Unsupported or invalid video")
    try:
        data = json.loads(probe.stdout.decode("utf-8", "replace"))
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=422, detail="Video metadata could not be read") from exc
    streams = data.get("streams") or []
    has_audio = any(stream.get("codec_type") == "audio" for stream in streams)
    duration = _positive_float((data.get("format") or {}).get("duration"))
    if duration is None:
        # Some containers (e.g. streamed WebM) have no format duration: use stream durations.
        candidates = [_positive_float(stream.get("duration")) for stream in streams]
        known = [value for value in candidates if value]
        duration = max(known) if known else None
    if not any(stream.get("codec_type") == "video" for stream in streams):
        raise HTTPException(status_code=422, detail="No video stream found")
    if duration is None:
        raise HTTPException(status_code=503, detail="Video duration could not be verified")
    if duration > MAX_VIDEO_SECONDS:
        raise HTTPException(status_code=413, detail="Video duration must be 3 minutes or less")
    return duration, has_audio


def _frame_timestamps(duration: float | None) -> list[float]:
    if duration is None:
        raise HTTPException(status_code=503, detail="Video duration could not be verified")
    timestamps: list[float] = []
    for fraction in VIDEO_FRAME_FRACTIONS:
        timestamp = min(max(0.0, duration * fraction), max(0.0, duration - 0.05))
        if not any(abs(timestamp - previous) < 0.05 for previous in timestamps):
            timestamps.append(timestamp)
    return timestamps


def _extract_frame(video_path: Path, timestamp: float, out_path: Path, deadline: Deadline) -> bytes | None:
    tried: list[float] = []
    for candidate in (timestamp, max(timestamp - 0.25, 0.0)):
        if any(abs(candidate - previous) < 0.05 for previous in tried):
            continue
        tried.append(candidate)
        result = _run_tool(
            ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-threads", "1",
             "-ss", f"{candidate:.3f}", "-i", str(video_path), "-an", "-sn",
             "-frames:v", "1", "-vf", "scale='min(768,iw)':-2", "-q:v", "5", "-y", str(out_path)],
            deadline.timeout(FRAME_TIMEOUT),
        )
        if result.returncode == 0 and out_path.exists() and out_path.stat().st_size > 0:
            data = out_path.read_bytes()
            out_path.unlink(missing_ok=True)
            return data
    LOG.warning("Could not decode a frame near %.2f seconds", timestamp)
    return None


def _extract_transcript(video_path: Path, root: Path, deadline: Deadline) -> str | None:
    audio_path = root / "audio.mp3"
    try:
        extracted = _run_tool(
            ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-threads", "1",
             "-i", str(video_path), "-map", "0:a:0", "-vn", "-t", str(MAX_VIDEO_SECONDS),
             "-ac", "1", "-ar", "16000", "-b:a", "48k", "-y", str(audio_path)],
            deadline.timeout(AUDIO_EXTRACT_TIMEOUT),
        )
    except _StepTimeout as exc:
        raise HTTPException(status_code=503, detail="Video audio extraction timed out") from exc
    if extracted.returncode != 0 or not audio_path.exists() or audio_path.stat().st_size == 0:
        raise HTTPException(status_code=503, detail="Video audio could not be inspected")

    def post(timeout: float) -> Any:
        with audio_path.open("rb") as audio_file:
            return AUDIO_UPSTREAM.post(
                "https://api.openai.com/v1/audio/transcriptions",
                data={"model": AUDIO_MODEL, "response_format": "json"},
                files={"file": ("video-audio.mp3", audio_file, "audio/mpeg")},
                timeout=timeout,
            )

    try:
        response = _call_upstream(post, deadline, AUDIO_TIMEOUT, "Audio transcription")
    except _UpstreamRejected as exc:
        raise HTTPException(status_code=503, detail="Video audio transcription unavailable") from exc
    try:
        transcript = response.json().get("text")
    except (ValueError, AttributeError) as exc:
        raise HTTPException(status_code=503, detail="Video audio transcription unavailable") from exc
    if not isinstance(transcript, str):
        raise HTTPException(status_code=503, detail="Video audio transcription unavailable")
    return transcript


def _video_frames_and_transcript(video_bytes: bytes, deadline: Deadline) -> tuple[list[str], str | None]:
    """Sample visual frames and transcribe the audio track, in parallel.

    The upstream moderation endpoint accepts text/images, not native video/audio, so audio is
    transcribed separately and then evaluated as text together with the frames.
    """
    with tempfile.TemporaryDirectory(prefix="aloute-mod-") as temp_dir:
        root = Path(temp_dir)
        video_path = root / "input-video.bin"
        video_path.write_bytes(video_bytes)
        duration, has_audio = _probe_video(video_path, deadline)

        frame_futures = [
            _WORK_POOL.submit(_extract_frame, video_path, timestamp, root / f"frame-{index}.jpg", deadline)
            for index, timestamp in enumerate(_frame_timestamps(duration))
        ]
        audio_future = _WORK_POOL.submit(_extract_transcript, video_path, root, deadline) if has_audio else None
        all_futures: list[Future] = frame_futures + ([audio_future] if audio_future else [])
        try:
            frames: list[bytes] = []
            frame_timed_out = False
            for future in frame_futures:
                try:
                    data = future.result(timeout=deadline.timeout(FRAME_TIMEOUT + 1))
                except (_StepTimeout, FutureTimeout):
                    frame_timed_out = True
                    continue
                if data:
                    frames.append(data)
            transcript = audio_future.result(timeout=deadline.timeout(AUDIO_TIMEOUT + AUDIO_EXTRACT_TIMEOUT)) if audio_future else None
        except FutureTimeout as exc:
            raise HTTPException(status_code=503, detail="Video inspection timed out") from exc
        finally:
            for future in all_futures:
                future.cancel()
            # Running subprocesses own these files until their bounded operation exits.
            # The HTTP caller's wait remains bounded by moderate_media's Future timeout.
            from concurrent.futures import wait
            wait(all_futures)

        if frame_timed_out or len(frames) != len(frame_futures):
            # Fewer frames than planned would weaken moderation: fail closed and let the caller retry.
            raise HTTPException(status_code=503, detail="Video frame extraction timed out")
        if not frames:
            raise HTTPException(status_code=422, detail="Video could not be decoded")
        return [_image_data_url(frame) for frame in frames], transcript


# --------------------------------------------------------------------------------------
# Text pre-screen (deterministic, intentionally small and strict)
# --------------------------------------------------------------------------------------
def _normalize_text_for_rules(text: str) -> str:
    """Lower-case, strip Vietnamese diacritics and punctuation (for unambiguous phrases only)."""
    value = unicodedata.normalize("NFKD", text.lower()).replace("đ", "d")
    value = "".join(char for char in value if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]+", " ", value).strip()


def _phrase_regex(phrase: str, not_followed_by: tuple[str, ...] = ()) -> re.Pattern[str]:
    body = r"[\W_]+".join(re.escape(part) for part in phrase.split())
    tail = ""
    if not_followed_by:
        alternatives = "|".join(re.escape(word) for word in not_followed_by)
        tail = rf"(?![\W_]+(?:{alternatives})(?!\w))"
    return re.compile(rf"(?<!\w){body}(?!\w){tail}")


def _compile(items: tuple[Any, ...]) -> list[re.Pattern[str]]:
    return [_phrase_regex(*(item if isinstance(item, tuple) else (item,))) for item in items]


# Matched against diacritic-STRIPPED text. Only phrases that cannot collide with ordinary
# Vietnamese/English once diacritics are removed belong here.
_THREAT_STRIPPED = _compile((
    "toi se giet ban", "toi se giet may", "tao se giet may", "tao se giet ban",
    "i will kill you", "i ll kill you", "ill kill you", "i am going to kill you",
    "i want to kill you", "i will hurt you", "i am going to hurt you",
))
_ABUSE_STRIPPED = _compile((
    "dan don", "ngu si", "fuck you", "thang ngu",
    "you are an idiot", "you are stupid", "you are a moron",
    ("chet di", ("duoc", "song", "mat")),  # not "mệt chết đi được", "chết đi sống lại"
))
# Matched against diacritic-PRESERVING text, because the stripped forms collide with common
# words: "do ngủ quên", "con ngủ", "dột nát", "do súc vật kéo", "tôi sẽ dành bàn".
_THREAT_ACCENTED = _compile((
    "tôi sẽ đấm bạn", "tao sẽ đấm mày", "tôi sẽ đâm bạn", "tao sẽ đâm mày",
    "tôi sẽ đánh bạn", "tao sẽ đánh mày",
))
_ABUSE_ACCENTED = _compile((
    "đồ ngu", "con ngu", "đồ khốn", "dốt nát", "đồ súc vật",
    ("đồ chó", ("mèo", "cưng")),  # not "đồ chó mèo" (pet supplies)
))


def _deterministic_text_pre_screen(text: str) -> dict[str, Any] | None:
    stripped = _normalize_text_for_rules(text)
    accented = unicodedata.normalize("NFC", text.lower())
    if any(p.search(stripped) for p in _THREAT_STRIPPED) or any(p.search(accented) for p in _THREAT_ACCENTED):
        return {"label": "violence/threatening", "score": 0.90, "rule": "explicit-threat"}
    # Clearly abusive phrases stay reviewable (FLAGGED); only an explicit threat is HIDDEN.
    if any(p.search(stripped) for p in _ABUSE_STRIPPED) or any(p.search(accented) for p in _ABUSE_ACCENTED):
        return {"label": "harassment", "score": 0.68, "rule": "direct-abuse-lexicon"}
    return None


# --------------------------------------------------------------------------------------
# Provider call + scoring
# --------------------------------------------------------------------------------------
def _moderate_input(value: str | list[dict[str, Any]], deadline: Deadline | None = None) -> dict[str, Any]:
    if isinstance(value, str):
        local_decision = _deterministic_text_pre_screen(value)
        if local_decision is not None:
            LOG.info("Text moderation matched deterministic pre-screen rule=%s score=%s",
                     local_decision["rule"], local_decision["score"])
            return local_decision
    if deadline is None:
        deadline = Deadline(TEXT_BUDGET_SECONDS)

    def post(timeout: float) -> Any:
        return UPSTREAM.post(
            "https://api.openai.com/v1/moderations",
            json={"model": MODEL, "input": value},
            timeout=timeout,
        )

    try:
        response = _call_upstream(post, deadline, UPSTREAM_TIMEOUT, "Moderation")
    except _UpstreamRejected as exc:
        raise HTTPException(status_code=422, detail="Content could not be processed by the moderation provider") from exc
    try:
        payload = response.json()
    except (json.JSONDecodeError, ValueError) as exc:
        LOG.warning("Upstream moderation returned invalid JSON")
        raise HTTPException(status_code=503, detail="Invalid moderation provider response") from exc

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
