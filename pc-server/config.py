import json
import os
from pathlib import Path
from dataclasses import dataclass, asdict


def _default_save_dir() -> str:
    return str(Path.home() / "Desktop" / "FilePass_Received")


def _config_dir() -> Path:
    return Path(os.environ.get("APPDATA", Path.home())) / "FilePass"


def _config_path() -> Path:
    return _config_dir() / "config.json"


@dataclass
class AppConfig:
    port: int = 8765
    save_dir: str = ""
    max_file_size_mb: int = 500
    auto_start: bool = True
    silent_mode: bool = True

    def __post_init__(self):
        if not self.save_dir:
            self.save_dir = _default_save_dir()

    @property
    def max_file_size_bytes(self) -> int:
        return self.max_file_size_mb * 1024 * 1024

    def save(self):
        path = _config_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(asdict(self), indent=2, ensure_ascii=False), encoding="utf-8")

    @classmethod
    def load(cls) -> "AppConfig":
        path = _config_path()
        if path.exists():
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                cfg = cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})
                return cfg
            except (json.JSONDecodeError, TypeError):
                pass
        cfg = cls()
        cfg.save()
        return cfg
