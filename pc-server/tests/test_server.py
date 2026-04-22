"""测试 server.py — API 端点"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import io
import pytest
from unittest.mock import patch
from pathlib import Path


pytestmark = pytest.mark.asyncio


TEST_TOKEN = "test-token-12345"


# ── /api/ping ──

async def test_ping(client):
    resp = await client.get("/api/ping")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "name" in data
    assert data["version"] == "1.0.0"


# ── /api/text ──

async def test_text_success(client, auth_headers):
    with patch("server.copy_to_clipboard") as mock_clip, \
         patch("server.notify"):
        resp = await client.post(
            "/api/text",
            json={"content": "Hello from phone!"},
            headers=auth_headers,
        )
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["length"] == len("Hello from phone!")
    mock_clip.assert_called_once_with("Hello from phone!")


async def test_text_empty(client, auth_headers):
    with patch("server.copy_to_clipboard"), patch("server.notify"):
        resp = await client.post(
            "/api/text",
            json={"content": ""},
            headers=auth_headers,
        )
    assert resp.status_code == 400


async def test_text_no_auth(client):
    resp = await client.post("/api/text", json={"content": "test"})
    assert resp.status_code == 422 or resp.status_code == 401


async def test_text_wrong_token(client):
    resp = await client.post(
        "/api/text",
        json={"content": "test"},
        headers={"Authorization": "Bearer wrong-token"},
    )
    assert resp.status_code == 401


# ── /api/file ──

async def test_file_upload(client, auth_headers, config):
    content = b"fake image content here"
    with patch("server.notify"):
        resp = await client.post(
            "/api/file",
            files={"file": ("test_photo.jpg", io.BytesIO(content), "image/jpeg")},
            headers=auth_headers,
        )
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["filename"] == "test_photo.jpg"
    assert data["size"] == len(content)

    # 验证文件确实被保存
    saved = Path(data["saved_to"])
    assert saved.exists()
    assert saved.read_bytes() == content


async def test_file_duplicate_name(client, auth_headers, config):
    """上传同名文件应自动追加序号。"""
    content1 = b"file v1"
    content2 = b"file v2"
    with patch("server.notify"):
        resp1 = await client.post(
            "/api/file",
            files={"file": ("dup.txt", io.BytesIO(content1), "text/plain")},
            headers=auth_headers,
        )
        resp2 = await client.post(
            "/api/file",
            files={"file": ("dup.txt", io.BytesIO(content2), "text/plain")},
            headers=auth_headers,
        )
    assert resp1.status_code == 200
    assert resp2.status_code == 200
    assert resp1.json()["filename"] != resp2.json()["filename"] or \
           resp1.json()["saved_to"] != resp2.json()["saved_to"]


async def test_file_too_large(client, auth_headers, config):
    """超过大小限制应返回 413。"""
    big_content = b"x" * (config.max_file_size_bytes + 1)
    with patch("server.notify"):
        resp = await client.post(
            "/api/file",
            files={"file": ("big.bin", io.BytesIO(big_content), "application/octet-stream")},
            headers=auth_headers,
        )
    assert resp.status_code == 413


async def test_file_path_traversal(client, auth_headers, config):
    """文件名含路径穿越字符应被清洗。"""
    content = b"safe content"
    with patch("server.notify"):
        resp = await client.post(
            "/api/file",
            files={"file": ("../../evil.txt", io.BytesIO(content), "text/plain")},
            headers=auth_headers,
        )
    assert resp.status_code == 200
    saved_path = resp.json()["saved_to"]
    # 文件必须保存在 save_dir 内
    assert str(Path(config.save_dir).resolve()) in str(Path(saved_path).resolve())


async def test_file_no_auth(client):
    resp = await client.post(
        "/api/file",
        files={"file": ("test.txt", io.BytesIO(b"data"), "text/plain")},
    )
    assert resp.status_code == 422 or resp.status_code == 401


# ── /api/clipboard ──

async def test_get_clipboard(client, auth_headers):
    with patch("server.get_clipboard", return_value="pc clipboard text"):
        resp = await client.get("/api/clipboard", headers=auth_headers)
    assert resp.status_code == 200
    assert resp.json()["content"] == "pc clipboard text"
