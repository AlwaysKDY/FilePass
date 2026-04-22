import sys
import logging

logger = logging.getLogger("filepass")


def notify(title: str, message: str) -> None:
    """发送 Windows Toast 通知，不抢焦点。"""
    if sys.platform != "win32":
        logger.info(f"[通知] {title}: {message}")
        return
    try:
        from plyer import notification
        notification.notify(
            title=title,
            message=message,
            app_name="FilePass",
            timeout=3,
        )
    except Exception as e:
        logger.warning(f"通知发送失败: {e}")
