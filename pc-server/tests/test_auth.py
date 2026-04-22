"""测试 — 验证所有端点无需认证即可访问"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from unittest.mock import patch


pytestmark = pytest.mark.asyncio


async def test_ping_no_auth(client):
    resp = await client.get("/api/ping")
    assert resp.status_code == 200


async def test_text_no_auth(client):
    with patch("server.copy_to_clipboard"), patch("server.notify"):
        resp = await client.post("/api/text", json={"content": "test"})
    assert resp.status_code == 200


async def test_clipboard_no_auth(client):
    with patch("server.get_clipboard", return_value="text"):
        resp = await client.get("/api/clipboard")
    assert resp.status_code == 200


async def test_file_no_auth(client, config):
    with patch("server.notify"):
        resp = await client.post(
            "/api/file",
            files={"file": ("test.txt", b"data", "text/plain")},
        )
    assert resp.status_code == 200
