"""API keys encrypted for the current Windows user with DPAPI."""
import base64
import ctypes
import json
import os
from pathlib import Path

from .providers import ENV_KEYS

SECRET_PATH = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "Kartal" / "keys.json"


def _crypt(data, decrypt=False):
    class Blob(ctypes.Structure):
        _fields_ = [("size", ctypes.c_ulong), ("data", ctypes.POINTER(ctypes.c_ubyte))]
    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    target = Blob()
    function = ctypes.windll.crypt32.CryptUnprotectData if decrypt else ctypes.windll.crypt32.CryptProtectData
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise OSError("Не удалось открыть защищённое хранилище ключей Windows.")
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        ctypes.windll.kernel32.LocalFree(ctypes.cast(target.data, ctypes.c_void_p))


def read_keys():
    try:
        stored = json.loads(SECRET_PATH.read_text(encoding="utf-8"))
        return {p: _crypt(base64.b64decode(v), True).decode() for p, v in stored.items()}
    except (OSError, ValueError, AttributeError):
        return {}


def get_key(provider):
    return read_keys().get(provider) or os.environ.get(ENV_KEYS[provider], "")


def save_keys(keys):
    encrypted = {p: base64.b64encode(_crypt(key.strip().encode())).decode()
                 for p, key in keys.items() if key.strip()}
    SECRET_PATH.parent.mkdir(parents=True, exist_ok=True)
    temporary = SECRET_PATH.with_suffix(".tmp")
    temporary.write_text(json.dumps(encrypted), encoding="utf-8")
    temporary.replace(SECRET_PATH)
