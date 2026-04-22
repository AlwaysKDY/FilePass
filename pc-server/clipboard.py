import ctypes
import ctypes.wintypes
import time
import logging

logger = logging.getLogger("filepass")

CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

OpenClipboard = user32.OpenClipboard
OpenClipboard.argtypes = [ctypes.wintypes.HWND]
OpenClipboard.restype = ctypes.wintypes.BOOL

CloseClipboard = user32.CloseClipboard
CloseClipboard.argtypes = []
CloseClipboard.restype = ctypes.wintypes.BOOL

EmptyClipboard = user32.EmptyClipboard
EmptyClipboard.argtypes = []
EmptyClipboard.restype = ctypes.wintypes.BOOL

SetClipboardData = user32.SetClipboardData
SetClipboardData.argtypes = [ctypes.wintypes.UINT, ctypes.wintypes.HANDLE]
SetClipboardData.restype = ctypes.wintypes.HANDLE

GetClipboardData = user32.GetClipboardData
GetClipboardData.argtypes = [ctypes.wintypes.UINT]
GetClipboardData.restype = ctypes.wintypes.HANDLE

GlobalAlloc = kernel32.GlobalAlloc
GlobalAlloc.argtypes = [ctypes.wintypes.UINT, ctypes.c_size_t]
GlobalAlloc.restype = ctypes.wintypes.HANDLE

GlobalLock = kernel32.GlobalLock
GlobalLock.argtypes = [ctypes.wintypes.HANDLE]
GlobalLock.restype = ctypes.c_void_p

GlobalUnlock = kernel32.GlobalUnlock
GlobalUnlock.argtypes = [ctypes.wintypes.HANDLE]
GlobalUnlock.restype = ctypes.wintypes.BOOL


def copy_to_clipboard(text: str) -> None:
    """使用 Win32 API 直接写入系统剪贴板（比 pyperclip 更可靠）。"""
    encoded = (text + "\0").encode("utf-16-le")
    h = GlobalAlloc(GMEM_MOVEABLE, len(encoded))
    if not h:
        raise OSError("GlobalAlloc failed")
    p = GlobalLock(h)
    if not p:
        raise OSError("GlobalLock failed")
    ctypes.memmove(p, encoded, len(encoded))
    GlobalUnlock(h)

    for _ in range(10):
        if OpenClipboard(0):
            break
        time.sleep(0.05)
    else:
        raise OSError("OpenClipboard failed after retries")

    EmptyClipboard()
    if not SetClipboardData(CF_UNICODETEXT, h):
        CloseClipboard()
        raise OSError("SetClipboardData failed")
    CloseClipboard()
    logger.info(f"已写入剪贴板 ({len(text)} 字)")


def get_clipboard() -> str:
    """使用 Win32 API 直接读取系统剪贴板。"""
    for _ in range(10):
        if OpenClipboard(0):
            break
        time.sleep(0.05)
    else:
        raise OSError("OpenClipboard failed after retries")

    try:
        h = GetClipboardData(CF_UNICODETEXT)
        if not h:
            return ""
        p = GlobalLock(h)
        if not p:
            return ""
        text = ctypes.wstring_at(p)
        GlobalUnlock(h)
        return text
    finally:
        CloseClipboard()
