import pyperclip


def copy_to_clipboard(text: str) -> None:
    """将文本写入系统剪贴板。"""
    pyperclip.copy(text)


def get_clipboard() -> str:
    """读取系统剪贴板文本。"""
    return pyperclip.paste()
