import os
import subprocess
import logging
from pathlib import Path

import pystray
from PIL import Image, ImageDraw

from config import AppConfig
from utils import get_local_ip

logger = logging.getLogger("filepass")


def _create_default_icon() -> Image.Image:
    """生成一个简易的默认托盘图标（绿色圆点 + F 字母）。"""
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.ellipse([4, 4, 60, 60], fill=(34, 197, 94))
    draw.text((20, 12), "F", fill="white")
    return img


def _load_icon() -> Image.Image:
    icon_path = Path(__file__).parent / "assets" / "icon.png"
    if icon_path.exists():
        try:
            return Image.open(icon_path)
        except Exception:
            pass
    return _create_default_icon()


class TrayApp:
    def __init__(self, config: AppConfig, on_quit):
        self.config = config
        self.on_quit = on_quit
        self.ip = get_local_ip()

    def _open_folder(self):
        folder = os.path.expanduser(self.config.save_dir)
        Path(folder).mkdir(parents=True, exist_ok=True)
        subprocess.Popen(["explorer", folder])

    def _show_token(self):
        logger.info(f"Token: {self.config.token}")

    def _quit(self, icon: pystray.Icon):
        icon.stop()
        if self.on_quit:
            self.on_quit()

    def run(self):
        icon = pystray.Icon(
            "FilePass",
            _load_icon(),
            f"FilePass - {self.ip}:{self.config.port}",
            menu=pystray.Menu(
                pystray.MenuItem(
                    f"IP: {self.ip}:{self.config.port}", None, enabled=False
                ),
                pystray.MenuItem(
                    f"Token: {self.config.token[:8]}...", None, enabled=False
                ),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("打开接收文件夹", lambda: self._open_folder()),
                pystray.MenuItem("退出", lambda: self._quit(icon)),
            ),
        )
        icon.run()
