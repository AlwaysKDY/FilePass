import socket
import logging
from zeroconf import Zeroconf, ServiceInfo

from utils import get_local_ip

logger = logging.getLogger("filepass")

SERVICE_TYPE = "_filepass._tcp.local."


class MdnsService:
    def __init__(self, port: int):
        self.port = port
        self.local_ip = get_local_ip()
        self.zc: Zeroconf | None = None
        self.info: ServiceInfo | None = None

    def register(self):
        self.zc = Zeroconf()
        hostname = socket.gethostname()
        self.info = ServiceInfo(
            type_=SERVICE_TYPE,
            name=f"FilePass-{hostname}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(self.local_ip)],
            port=self.port,
            properties={"version": "1.0"},
        )
        self.zc.register_service(self.info)
        logger.info(f"mDNS 已注册: {self.local_ip}:{self.port}")

    def unregister(self):
        if self.zc:
            self.zc.unregister_all_services()
            self.zc.close()
            self.zc = None
            logger.info("mDNS 已注销")
