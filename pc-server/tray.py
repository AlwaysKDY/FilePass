import os
import sys
import subprocess
import logging
import winreg
from pathlib import Path

import pystray
from PIL import Image, ImageDraw

from config import AppConfig
from utils import get_local_ip

logger = logging.getLogger("filepass")

APP_NAME = "FilePass"
REG_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def _get_exe_path() -> str:
    """获取当前可执行文件路径（兼容 PyInstaller 打包和直接运行）。"""
    if getattr(sys, "frozen", False):
        return sys.executable
    return f'"{sys.executable}" "{os.path.abspath(sys.argv[0])}"'


def _is_auto_start_enabled() -> bool:
    """检查注册表中是否已设置开机自启。"""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_RUN_KEY, 0, winreg.KEY_READ) as key:
            winreg.QueryValueEx(key, APP_NAME)
            return True
    except FileNotFoundError:
        return False
    except OSError:
        return False


def _set_auto_start(enable: bool) -> None:
    """写入或删除注册表开机自启项。"""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            if enable:
                winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, _get_exe_path())
                logger.info("已启用开机自启")
            else:
                try:
                    winreg.DeleteValue(key, APP_NAME)
                except FileNotFoundError:
                    pass
                logger.info("已禁用开机自启")
    except OSError as e:
        logger.error(f"设置开机自启失败: {e}")


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

    def _toggle_silent(self):
        self.config.silent_mode = not self.config.silent_mode
        self.config.save()
        state = "开启" if self.config.silent_mode else "关闭"
        logger.info(f"静默模式已{state}")

    def _toggle_auto_start(self):
        new_state = not _is_auto_start_enabled()
        _set_auto_start(new_state)
        self.config.auto_start = new_state
        self.config.save()

    def _quit(self, icon: pystray.Icon):
        icon.stop()
        if self.on_quit:
            self.on_quit()

    def run(self):
        logger.info("TrayApp.run() 开始")
        img = _load_icon()
        logger.info(f"图标已加载: size={img.size}, mode={img.mode}")

        icon = pystray.Icon(
            "FilePass",
            img,
            f"FilePass - {self.ip}:{self.config.port}",
            menu=pystray.Menu(
                pystray.MenuItem(
                    f"{self.ip}:{self.config.port}", None, enabled=False
                ),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("打开接收文件夹", lambda: self._open_folder()),
                pystray.MenuItem("退出", lambda: self._quit(icon)),
            ),
        )
        logger.info("pystray.Icon 对象已创建，即将调用 icon.run()")
        try:
            icon.run()
        except Exception:
            import traceback
            logger.critical(f"icon.run() 异常:\n{traceback.format_exc()}")
        logger.info("icon.run() 已返回（托盘已退出）")
