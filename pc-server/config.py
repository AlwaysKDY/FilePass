import json
import os
from pathlib import Path
from dataclasses import dataclass, asdict


def _default_save_dir() -> str:
    return str(Path.home() / "Desktop" / "FilePass_Received")


def _default_send_to_phone_dir() -> str:
    """优先选 D: 或 E: 盘，避免占用系统盘（C:）。必须使用带反斜杠的绝对路径。"""
    for drive in ["D:", "E:", "F:"]:
        try:
            root = Path(drive + "\\")
            if root.exists():
                return str(root / "FilePass_ToPhone")
        except Exception:
            pass
    return str(Path.home() / "Desktop" / "FilePass_ToPhone")


def _config_dir() -> Path:
    return Path(os.environ.get("APPDATA", Path.home())) / "FilePass"


def _config_path() -> Path:
    return _config_dir() / "config.json"


@dataclass
class AppConfig:
    port: int = 8765
    save_dir: str = ""
    send_to_phone_dir: str = ""
    max_file_size_mb: int = 500
    auto_start: bool = True
    silent_mode: bool = True

    def __post_init__(self):
        if not self.save_dir:
            self.save_dir = _default_save_dir()
        # 若目录为空、非绝对路径（缺反斜杠等）或在 C 盘，则重新生成
        p = Path(self.send_to_phone_dir) if self.send_to_phone_dir else None
        if not p or not p.is_absolute() or p.drive.upper() == "C:":
            self.send_to_phone_dir = _default_send_to_phone_dir()

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
                cfg.save()  # 持久化 __post_init__ 中可能迁移过的字段
                return cfg
            except (json.JSONDecodeError, TypeError):
                pass
        cfg = cls()
        cfg.save()
        return cfg
