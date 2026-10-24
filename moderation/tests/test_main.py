import os

os.environ.setdefault("OPENAI_API_KEY", "test-key-not-for-real-use")
os.environ.setdefault("MODERATION_API_TOKEN", "test-only-token-" + "x" * 40)
os.environ.setdefault("MODERATION_ALLOWED_MEDIA_HOSTS", "res.cloudinary.com")

from fastapi.testclient import TestClient
import main

client = TestClient(main.app)
TOKEN = os.environ["MODERATION_API_TOKEN"]


class FakeResponse:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code

    def raise_for_status(self):
        if self.status_code >= 400:
            raise main.httpx.HTTPStatusError("provider failure", request=main.httpx.Request("POST", "https://api.openai.com"), response=main.httpx.Response(self.status_code))

    def json(self):
        return self._payload


def test_moderate_requires_internal_token():
    response = client.post("/moderate", json={"text": "xin chao"})
    assert response.status_code == 401


def test_safe_text_returns_approved_band(monkeypatch):
    monkeypatch.setattr(main.UPSTREAM, "post", lambda *args, **kwargs: FakeResponse({"results": [{"flagged": False, "category_scores": {"harassment": 0.04, "hate": 0.01}}]}))
    response = client.post("/moderate", headers={"X-Moderation-Token": TOKEN}, json={"text": "Xin chào"})
    assert response.status_code == 200
    assert response.json()["label"] == "safe"
    assert response.json()["score"] < 0.5


def test_flagged_text_maps_to_flagged_band(monkeypatch):
    monkeypatch.setattr(main.UPSTREAM, "post", lambda *args, **kwargs: FakeResponse({"results": [{"flagged": True, "category_scores": {"harassment": 0.73, "hate": 0.11}}]}))
    response = client.post("/moderate", headers={"X-Moderation-Token": TOKEN}, json={"text": "unsafe"})
    assert response.status_code == 200
    assert response.json()["label"] == "harassment"
    assert response.json()["score"] == 0.73


def test_media_url_allowlist_rejects_local_hosts():
    response = client.post(
        "/moderate-media",
        headers={"X-Moderation-Token": TOKEN},
        json={"url": "http://127.0.0.1:8080/actuator/health", "mediaType": "image"},
    )
    assert response.status_code == 422


def test_empty_upstream_result_fails_closed(monkeypatch):
    monkeypatch.setattr(main.UPSTREAM, "post", lambda *args, **kwargs: FakeResponse({"results": []}))
    response = client.post("/moderate", headers={"X-Moderation-Token": TOKEN}, json={"text": "content"})
    assert response.status_code == 503
