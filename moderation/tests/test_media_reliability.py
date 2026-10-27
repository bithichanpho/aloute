import os
os.environ.setdefault("OPENAI_API_KEY", "test-key-not-for-real-use")
os.environ.setdefault("MODERATION_API_TOKEN", "test-only-token-" + "x" * 40)

import io
import json
import socket
from concurrent.futures import Future, ThreadPoolExecutor
from threading import Event
from types import SimpleNamespace
import pytest
from PIL import Image
import main


@pytest.fixture(autouse=True)
def isolated(monkeypatch):
    monkeypatch.setattr(socket.socket, "connect", lambda *a, **k: pytest.fail("No external network in tests"))
    monkeypatch.setattr(main.time, "sleep", lambda _: None)
    with main._FLIGHT_LOCK:
        main._CACHE.clear()
        main._INFLIGHT.clear()
    yield
    with main._FLIGHT_LOCK:
        main._CACHE.clear()
        main._INFLIGHT.clear()


def frame():
    out = io.BytesIO()
    Image.new("RGB", (2, 2), "blue").save(out, format="JPEG")
    return out.getvalue()


def test_audio_extraction_failure_cannot_approve_visual_frames(monkeypatch):
    monkeypatch.setattr(main, "_download_media", lambda *a: b"video fixture")
    monkeypatch.setattr(main, "_probe_video", lambda *a: (20.0, True))
    monkeypatch.setattr(main, "_extract_frame", lambda *a: frame())
    monkeypatch.setattr(main, "_run_tool", lambda *a: SimpleNamespace(returncode=1))
    monkeypatch.setattr(main, "_moderate_input", lambda *a: pytest.fail("Incomplete audio must not reach verdict"))
    with pytest.raises(main.HTTPException) as err:
        main._run_media_moderation("https://res.cloudinary.com/test-video", "video")
    assert err.value.status_code == 503


@pytest.mark.parametrize("status", [400, 413, 422])
def test_rejected_audio_transcription_is_retryable_not_silent_approval(monkeypatch, tmp_path, status):
    (tmp_path / "audio.mp3").write_bytes(b"fake audio")
    monkeypatch.setattr(main, "_run_tool", lambda *a: SimpleNamespace(returncode=0))
    def rejected(*a):
        raise main._UpstreamRejected(status)
    monkeypatch.setattr(main, "_call_upstream", rejected)
    with pytest.raises(main.HTTPException) as err:
        main._extract_transcript(tmp_path / "video.bin", tmp_path, main.Deadline(5))
    assert err.value.status_code == 503


@pytest.mark.parametrize("duration,expected", [(None, 503), (181, 413)])
def test_unknown_or_excessive_duration_cannot_be_approved(monkeypatch, tmp_path, duration, expected):
    metadata = {"streams": [{"codec_type": "video"}], "format": {"duration": duration}}
    monkeypatch.setattr(main, "_run_tool", lambda *a: SimpleNamespace(returncode=0, stdout=json.dumps(metadata).encode()))
    with pytest.raises(main.HTTPException) as err:
        main._probe_video(tmp_path / "video.bin", main.Deadline(5))
    assert err.value.status_code == expected


def test_missing_late_frame_cannot_approve_only_first_frame(monkeypatch):
    monkeypatch.setattr(main, "_probe_video", lambda *a: (100.0, False))
    monkeypatch.setattr(main, "_extract_frame", lambda path, timestamp, *a: frame() if timestamp < 20 else None)
    with pytest.raises(main.HTTPException) as err:
        main._video_frames_and_transcript(b"video fixture", main.Deadline(5))
    assert err.value.status_code == 503


def test_silent_video_with_all_frames_is_moderated(monkeypatch):
    monkeypatch.setattr(main, "_probe_video", lambda *a: (20.0, False))
    monkeypatch.setattr(main, "_extract_frame", lambda *a: frame())
    frames, transcript = main._video_frames_and_transcript(b"fixture", main.Deadline(5))
    assert len(frames) == 3 and transcript is None


def test_same_media_single_flight_under_concurrent_requests(monkeypatch):
    release, started = Event(), Event()
    calls = []
    def work(key):
        calls.append(key); started.set()
        assert release.wait(5)
        return {"label": "safe", "score": 0.01}
    monkeypatch.setattr(main, "_media_job", work)
    key = ("https://res.cloudinary.com/concurrent", "image")
    try:
        with ThreadPoolExecutor(max_workers=8) as requests:
            futures = list(requests.map(lambda _: main._submit_media_job(key), range(16)))
        assert started.wait(2)
        assert all(f is futures[0] for f in futures)
        assert len(calls) == 1
    finally:
        release.set()
    assert futures[0].result(timeout=5)["label"] == "safe"


def test_pending_limit_is_retryable(monkeypatch):
    monkeypatch.setattr(main, "MAX_PENDING_MEDIA_JOBS", 1)
    main._INFLIGHT[("first", "image")] = Future()
    with pytest.raises(main.HTTPException) as err:
        main._submit_media_job(("second", "video"))
    assert err.value.status_code == 503 and err.value.headers["Retry-After"] == "5"


def test_failures_never_enter_result_cache(monkeypatch):
    key = ("https://res.cloudinary.com/failed", "image")
    def unavailable(*a):
        raise main.HTTPException(status_code=503)
    monkeypatch.setattr(main, "_run_media_moderation", unavailable)
    with pytest.raises(main.HTTPException): main._media_job(key)
    assert main._cache_get(key) is None


def test_transient_provider_error_retries_within_budget():
    calls = []
    responses = [SimpleNamespace(status_code=503, headers={}), SimpleNamespace(status_code=200, headers={})]
    def provider(timeout):
        calls.append(timeout)
        return responses.pop(0)
    assert main._call_upstream(provider, main.Deadline(5), 2, "test").status_code == 200
    assert len(calls) == 2


def test_redirect_to_unlisted_host_is_rejected(monkeypatch):
    response = SimpleNamespace(status_code=302, headers={"location": "https://127.0.0.1/private"})
    class Stream:
        def __enter__(self): return response
        def __exit__(self, *a): pass
    monkeypatch.setattr(main.DOWNLOADER, "stream", lambda *a, **k: Stream())
    with pytest.raises(main.HTTPException) as err:
        main._download_once("https://res.cloudinary.com/test", 1000, main.Deadline(5), 2)
    assert err.value.status_code == 422
