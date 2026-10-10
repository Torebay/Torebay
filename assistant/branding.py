"""Shared Kartal identity for Windows title bars and taskbar."""
from pathlib import Path
import sys


def apply_icon(window):
    if sys.platform == 'win32':
        import ctypes
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID('Kartal.Assistant.1')
    icon = Path(__file__).resolve().parent.parent / 'assets' / 'kartal.ico'
    if icon.exists():
        window.iconbitmap(default=str(icon))
