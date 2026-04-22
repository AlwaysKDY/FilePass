import sys
import os
import socket
import subprocess
import logging
import multiprocessing
import threading
import traceback
import time
from pathlib import Path

import uvicorn

from config import AppConfig, _config_dir
from server import create_app
from mdns_service import MdnsService
from tray import TrayApp
from utils import get_local_ip

_LOG_DIR = _config_dir()
_LOG_DIR.mkdir(parents=True, exist_ok=True)
_LOG_FILE = _LOG_DIR / "filepass.log"


def _hide_console():
    """打包为 EXE 时隐藏控制台窗口（使用 console=True 编译以保证 stdout/stderr 可用）。"""
    if not getattr(sys, "frozen", False):
        return
    try:
        import ctypes
        hwnd = ctypes.windll.kernel32.GetConsoleWindow()
        if hwnd:
            ctypes.windll.user32.ShowWindow(hwnd, 0)  # SW_HIDE
    except Exception:
        pass


class _FlushFileHandler(logging.FileHandler):
    """每条日志写入后立即 flush，确保崩溃前日志不丢失。"""
    def emit(self, record):
        super().emit(record)
        self.flush()


def _setup_logging():
    """配置日志：同时输出到文件 %APPDATA%/FilePass/filepass.log"""
    fmt = logging.Formatter("%(asctime)s [%(name)s] %(levelname)s: %(message)s")

    fh = _FlushFileHandler(str(_LOG_FILE), mode="w", encoding="utf-8")
    fh.setLevel(logging.DEBUG)
    fh.setFormatter(fmt)

    root = logging.getLogger()
    root.setLevel(logging.DEBUG)
    root.addHandler(fh)

    logging.getLogger("PIL").setLevel(logging.WARNING)
    logging.getLogger("asyncio").setLevel(logging.WARNING)

    if not getattr(sys, "frozen", False):
        ch = logging.StreamHandler()
        ch.setLevel(logging.INFO)
        ch.setFormatter(fmt)
        root.addHandler(ch)

    return _LOG_FILE


logger = logging.getLogger("filepass")


def _kill_stale_instance(port: int) -> None:
    """若端口已被占用，终止占用该端口的旧进程（单例保证）。"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(1)
    in_use = (sock.connect_ex(("127.0.0.1", port)) == 0)
    sock.close()

    if not in_use:
        return

    logger.warning(f"端口 {port} 已被旧实例占用，正在终止旧进程…")
    try:
        out = subprocess.check_output(
            ["netstat", "-ano"],
            text=True, encoding="gbk", errors="ignore"
        )
        for line in out.splitlines():
            if f":{port}" in line and "LISTENING" in line:
                parts = line.strip().split()
                if parts:
                    pid = int(parts[-1])
                    if pid != os.getpid():
                        subprocess.run(
                            ["taskkill", "/F", "/PID", str(pid)],
                            capture_output=True
                        )
                        logger.info(f"已终止旧实例 PID={pid}")
        time.sleep(0.8)
    except Exception:
        logger.warning(f"终止旧实例失败（请手动关闭旧进程）:\n{traceback.format_exc()}")


def _ensure_firewall_rule(port: int) -> None:
    """尝试添加 Windows 防火墙规则放行端口（首次运行时执行，需要管理员权限）。"""
    rule_name = "FilePass-Server"
    try:
        result = subprocess.run(
            ["netsh", "advfirewall", "firewall", "show", "rule", f"name={rule_name}"],
            capture_output=True, text=True, encoding="gbk", errors="ignore"
        )
        if rule_name not in result.stdout:
            ret = subprocess.run([
                "netsh", "advfirewall", "firewall", "add", "rule",
                f"name={rule_name}", "dir=in", "action=allow",
                "protocol=TCP", f"localport={port}",
                "profile=private,domain,public",
            ], capture_output=True)
            if ret.returncode == 0:
                logger.info(f"防火墙规则已添加：允许 TCP 端口 {port} 入站")
            else:
                logger.warning(
                    f"防火墙规则添加失败（返回码 {ret.returncode}）。"
                    f"请手动允许或以管理员身份运行一次。"
                    f"\n建议命令: netsh advfirewall firewall add rule "
                    f"name={rule_name} dir=in action=allow protocol=TCP localport={port}"
                )
        else:
            logger.info("防火墙规则已存在，跳过")
    except Exception:
        logger.warning(f"检查/添加防火墙规则时出错:\n{traceback.format_exc()}")


def run_server(config: AppConfig):
    logger.info("FastAPI 服务线程启动…")
    try:
        app = create_app(config)
        logger.info(f"uvicorn 即将监听 0.0.0.0:{config.port}")
        uvicorn.run(
            app,
            host="0.0.0.0",
            port=config.port,
            log_level="warning",
            log_config=None,
        )
    except Exception:
        logger.critical(f"FastAPI 服务线程异常:\n{traceback.format_exc()}")


def _thread_excepthook(args):
    """捕获所有线程未处理异常，写入日志。"""
    logger.critical(
        f"线程 {args.thread.name} 未处理异常:\n"
        f"{''.join(traceback.format_exception(args.exc_type, args.exc_value, args.exc_traceback))}"
    )


def main():
    _hide_console()
    log_file = _setup_logging()
    threading.excepthook = _thread_excepthook
    logger.info("=" * 60)
    logger.info("FilePass 进程启动")
    logger.info(f"  PID       : {os.getpid()}")
    logger.info(f"  frozen    : {getattr(sys, 'frozen', False)}")
    logger.info(f"  executable: {sys.executable}")
    logger.info(f"  日志文件  : {log_file}")
    logger.info("=" * 60)

    try:
        config = AppConfig.load()
        logger.info(f"配置已加载: port={config.port}, save_dir={config.save_dir}, "
                     f"silent_mode={config.silent_mode}, auto_start={config.auto_start}")
    except Exception:
        logger.critical(f"加载配置失败:\n{traceback.format_exc()}")
        return

    try:
        ip = get_local_ip()
        logger.info(f"局域网 IP: {ip}")
    except Exception:
        logger.critical(f"获取本机 IP 失败:\n{traceback.format_exc()}")
        ip = "127.0.0.1"

    # 0. 终止旧实例 + 确保防火墙
    _kill_stale_instance(config.port)
    _ensure_firewall_rule(config.port)

    logger.info(f"  端口      : {config.port}")
    logger.info(f"  保存目录  : {config.save_dir}")

    # 1. 后台线程启动 FastAPI
    server_thread = threading.Thread(target=run_server, args=(config,), daemon=True)
    server_thread.start()
    logger.info("FastAPI 服务线程已创建并启动")

    time.sleep(0.5)
    logger.info(f"服务线程存活: {server_thread.is_alive()}")

    # 2. mDNS 注册
    mdns = MdnsService(config.port)
    try:
        mdns.register()
        logger.info("mDNS 注册成功")
    except Exception:
        logger.warning(f"mDNS 注册失败（不影响使用，可手动输入IP）:\n{traceback.format_exc()}")

    # 3. 清理回调 — 使用 os._exit(0) 强制终止整个进程
    #    sys.exit() 在 pystray 回调线程中只终止该线程，无法终止主线程和 uvicorn 非 daemon 线程
    def on_quit():
        logger.info("用户点击退出，强制终止进程")
        try:
            mdns.unregister()
        except Exception:
            pass
        os._exit(0)

    # 4. 托盘（主线程阻塞）
    logger.info("即将启动系统托盘…")
    try:
        tray = TrayApp(config, on_quit)
        logger.info("TrayApp 对象已创建，调用 tray.run()")
        tray.run()
    except Exception:
        logger.critical(f"系统托盘异常:\n{traceback.format_exc()}")
        os._exit(1)

    # 若 tray.run() 正常返回（不应到达此处）
    os._exit(0)


if __name__ == "__main__":
    multiprocessing.freeze_support()
    try:
        main()
    except Exception:
        crash_path = _LOG_DIR / "crash.log"
        crash_path.write_text(traceback.format_exc(), encoding="utf-8")
        raise
