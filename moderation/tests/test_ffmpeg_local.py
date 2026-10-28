"""Local processor integration; synthetic media and fake provider, no uploads or real API calls."""
import os
import shutil
import subprocess
from types import SimpleNamespace

os.environ.setdefault("OPENAI_API_KEY", "test-key-not-for-real-use")
os.environ.setdefault("MODERATION_API_TOKEN", "test-only-token-" + "x" * 40)

import pytest
import main


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"), reason="Local FFmpeg tools required")
def test_real_video_frames_and_audio_are_both_reviewed(monkeypatch, tmp_path):
    clip = tmp_path / "synthetic.mp4"
    subprocess.run([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", "color=c=blue:s=160x120:r=10",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000",
        "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-y", str(clip)
    ], check=True, timeout=15, capture_output=True)
    transcript_calls = []
    def transcript(*args, **kwargs):
        audio_file = kwargs["files"]["file"][1]
        assert len(audio_file.read()) > 0
        transcript_calls.append(True)
        return SimpleNamespace(status_code=200, json=lambda: {"text": "synthetic audio fixture"})
    monkeypatch.setattr(main.AUDIO_UPSTREAM, "post", transcript)
    monkeypatch.setattr(main, "_download_media", lambda *args: clip.read_bytes())
    inputs = []
    def verdict(value, deadline):
        inputs.extend(value)
        return {"label": "safe", "score": 0.01}
    monkeypatch.setattr(main, "_moderate_input", verdict)

    assert main._run_media_moderation("https://res.cloudinary.com/test-only", "video")["label"] == "safe"
    assert transcript_calls == [True]
    assert len([item for item in inputs if item["type"] == "image_url"]) == 3
    assert any(item["type"] == "text" and "synthetic audio fixture" in item["text"] for item in inputs)
