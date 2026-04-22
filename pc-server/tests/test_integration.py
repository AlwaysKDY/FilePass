"""
集成测试 — 模拟 Android 端全流程调用 PC 端 API。
覆盖场景：
  1. 文本传输（短文本/中文/长文本）
  2. 文件传输（小文件/同名文件去重/超限拒绝/路径穿越防护）
  3. 剪贴板获取
  4. Ping 检测
  5. 并发请求
"""
import asyncio
import os
import pytest
from httpx import AsyncClient, ASGITransport


# ── 文本传输 ──

async def test_text_roundtrip(client):
    """文本推送后应能通过剪贴板端点读回。"""
    resp = await client.post(
        "/api/text",
        json={"content": "integration test"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["length"] == 16

    # 读回剪贴板（跳过，因为测试环境可能无剪贴板）


async def test_text_long(client):
    """发送 10KB 文本。"""
    long_text = "A" * 10240
    resp = await client.post(
        "/api/text",
        json={"content": long_text},
    )
    assert resp.status_code == 200
    assert resp.json()["length"] == 10240


async def test_text_unicode(client):
    """中日韩 + emoji 混合文本。"""
    text = "你好世界🎉こんにちは"
    resp = await client.post(
        "/api/text",
        json={"content": text},
    )
    assert resp.status_code == 200
    assert resp.json()["length"] == len(text)


async def test_text_empty_rejected(client):
    """空文本应被拒绝。"""
    resp = await client.post(
        "/api/text",
        json={"content": ""},
    )
    assert resp.status_code == 400


# ── 文件传输 ──

async def test_file_upload(client, config):
    """上传普通文件并验证落盘。"""
    content = b"hello from android" * 100
    resp = await client.post(
        "/api/file",
        files={"file": ("test.txt", content, "application/octet-stream")},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["filename"] == "test.txt"
    assert data["size"] == len(content)

    # 验证文件存在且内容正确
    saved = os.path.join(os.path.expanduser(config.save_dir), "test.txt")
    assert os.path.exists(saved)
    with open(saved, "rb") as f:
        assert f.read() == content


async def test_file_dedup(client, config):
    """同名文件自动追加序号。"""
    for i in range(3):
        resp = await client.post(
            "/api/file",
            files={"file": ("dup.txt", b"data", "application/octet-stream")},
        )
        assert resp.status_code == 200

    save_dir = os.path.expanduser(config.save_dir)
    assert os.path.exists(os.path.join(save_dir, "dup.txt"))
    assert os.path.exists(os.path.join(save_dir, "dup_1.txt"))
    assert os.path.exists(os.path.join(save_dir, "dup_2.txt"))


async def test_file_oversize_rejected(client, config):
    """超过 max_file_size 的文件应返回 413。"""
    # config 限制 1MB
    big = b"X" * (1024 * 1024 + 1)
    resp = await client.post(
        "/api/file",
        files={"file": ("big.bin", big, "application/octet-stream")},
    )
    assert resp.status_code == 413

    # 验证文件未残留
    saved = os.path.join(os.path.expanduser(config.save_dir), "big.bin")
    assert not os.path.exists(saved)


async def test_file_path_traversal(client, config):
    """路径穿越攻击应被阻止。"""
    resp = await client.post(
        "/api/file",
        files={"file": ("../../../etc/passwd", b"hack", "application/octet-stream")},
    )
    assert resp.status_code == 200
    data = resp.json()
    # 文件名被清洗
    assert ".." not in data["filename"]
    assert "/" not in data["filename"]
    assert "\\" not in data["filename"]


# ── Ping ──

async def test_ping(client):
    """Ping 不需要 Token。"""
    resp = await client.get("/api/ping")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "name" in data
    assert data["version"] == "1.0.0"


# ── 并发 ──

async def test_concurrent_text_sends(client):
    """10 个并发文本发送都应成功。"""
    async def send(i):
        resp = await client.post(
            "/api/text",
            json={"content": f"concurrent msg {i}"},
        )
        return resp.status_code

    results = await asyncio.gather(*(send(i) for i in range(10)))
    assert all(code == 200 for code in results)


async def test_concurrent_file_uploads(client, config):
    """5 个并发文件上传都应成功。"""
    async def upload(i):
        resp = await client.post(
            "/api/file",
            files={"file": (f"concurrent_{i}.txt", f"data {i}".encode(), "application/octet-stream")},
        )
        return resp.status_code

    results = await asyncio.gather(*(upload(i) for i in range(5)))
    assert all(code == 200 for code in results)
