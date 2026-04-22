"""conftest — 测试公共 fixtures"""
import sys
import os
import tempfile
import pytest
from unittest.mock import patch

# 确保 pc-server 在 sys.path 中
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from config import AppConfig
from server import create_app


@pytest.fixture()
def config(tmp_path):
    """创建一个使用临时目录的测试配置。"""
    return AppConfig(
        port=18765,
        save_dir=str(tmp_path / "received"),
        max_file_size_mb=1,  # 测试用，限制 1MB
    )


@pytest.fixture()
def app(config):
    """创建 FastAPI 测试 app。"""
    return create_app(config)


@pytest.fixture()
def client(app):
    """创建 httpx 异步测试客户端。"""
    from httpx import AsyncClient, ASGITransport
    transport = ASGITransport(app=app)
    return AsyncClient(transport=transport, base_url="http://test")
