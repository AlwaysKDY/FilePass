"""测试 config.py"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import json
from config import AppConfig, _config_path


def test_config_defaults():
    cfg = AppConfig()
    assert cfg.port == 8765
    assert cfg.token  # 非空
    assert cfg.max_file_size_mb == 500
    assert cfg.max_file_size_bytes == 500 * 1024 * 1024


def test_config_save_load(tmp_path, monkeypatch):
    config_file = tmp_path / "config.json"
    monkeypatch.setattr("config._config_path", lambda: config_file)

    cfg = AppConfig(port=9999, token="abc123")
    cfg.save()

    assert config_file.exists()
    data = json.loads(config_file.read_text(encoding="utf-8"))
    assert data["port"] == 9999
    assert data["token"] == "abc123"

    loaded = AppConfig.load()
    assert loaded.port == 9999
    assert loaded.token == "abc123"


def test_config_auto_create(tmp_path, monkeypatch):
    config_file = tmp_path / "sub" / "config.json"
    monkeypatch.setattr("config._config_path", lambda: config_file)

    cfg = AppConfig.load()
    assert config_file.exists()
    assert cfg.token  # 自动生成
