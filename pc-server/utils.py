import os
import re
import socket
import time


def sanitize_filename(name: str) -> str:
    """清洗文件名，防止路径穿越攻击。"""
    name = os.path.basename(name)
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', name)
    if not name or name.startswith('.'):
        name = f"file_{int(time.time())}"
    return name


def get_local_ip() -> str:
    """获取本机局域网 IP 地址。"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"
