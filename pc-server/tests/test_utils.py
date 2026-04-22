"""测试 utils.py"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from utils import sanitize_filename, get_local_ip


class TestSanitizeFilename:
    def test_normal(self):
        assert sanitize_filename("photo.jpg") == "photo.jpg"

    def test_path_traversal(self):
        result = sanitize_filename("../../etc/passwd")
        assert ".." not in result
        assert "/" not in result
        assert "\\" not in result
        assert result == "passwd"

    def test_windows_path(self):
        result = sanitize_filename("C:\\Users\\test\\file.txt")
        assert result == "file.txt"

    def test_special_chars(self):
        result = sanitize_filename('file<>:"|?.txt')
        assert "<" not in result
        assert ">" not in result
        assert "?" not in result

    def test_empty(self):
        result = sanitize_filename("")
        assert result.startswith("file_")

    def test_dot_file(self):
        result = sanitize_filename(".hidden")
        assert result.startswith("file_")

    def test_unix_path(self):
        result = sanitize_filename("/tmp/evil/../../etc/shadow")
        assert result == "shadow"


class TestGetLocalIp:
    def test_returns_string(self):
        ip = get_local_ip()
        assert isinstance(ip, str)
        parts = ip.split(".")
        assert len(parts) == 4
