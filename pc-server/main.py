import sys
import logging
import threading
import uvicorn

from config import AppConfig
from server import create_app
from mdns_service import MdnsService
from tray import TrayApp
from utils import get_local_ip

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger("filepass")


def run_server(config: AppConfig):
    app = create_app(config)
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=config.port,
        log_level="warning",
    )


def main():
    config = AppConfig.load()
    ip = get_local_ip()

    logger.info(f"FilePass 启动中...")
    logger.info(f"  局域网 IP : {ip}")
    logger.info(f"  端口      : {config.port}")
    logger.info(f"  Token     : {config.token}")
    logger.info(f"  保存目录  : {config.save_dir}")

    # 1. 后台线程启动 FastAPI
    server_thread = threading.Thread(target=run_server, args=(config,), daemon=True)
    server_thread.start()

    # 2. mDNS 注册
    mdns = MdnsService(config.port)
    try:
        mdns.register()
    except Exception as e:
        logger.warning(f"mDNS 注册失败（不影响使用，可手动输入IP）: {e}")

    # 3. 清理回调
    def on_quit():
        mdns.unregister()
        logger.info("FilePass 已退出")
        sys.exit(0)

    # 4. 托盘（主线程阻塞）
    tray = TrayApp(config, on_quit)
    tray.run()


if __name__ == "__main__":
    main()
