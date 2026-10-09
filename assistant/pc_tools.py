"""Инструменты, которыми Claude управляет компьютером: громкость, музыка, папки, файлы,
выключение, снимок экрана и память.

Claude сам решает, когда их вызвать, поэтому команды понимаются на любом языке и в любой
форме («сделай потише», «ovozni pasaytir», «sesi kıs»).
"""

from __future__ import annotations

import base64
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

FOLDERS = {
    "downloads": "Downloads", "documents": "Documents", "desktop": "Desktop",
    "pictures": "Pictures", "music": "Music", "videos": "Videos",
}
# Клавиши мультимедиа Windows.
KEYS = {"volume_up": 0xAF, "volume_down": 0xAE, "mute": 0xAD,
        "play_pause": 0xB3, "next": 0xB0, "previous": 0xB1}
MAX_FOUND = 10
# Номера папок для SHGetFolderPathW: так находится и «OneDrive\Рабочий стол».
CSIDL = {"desktop": 0x10, "documents": 0x05, "pictures": 0x27, "music": 0x0D, "videos": 0x0E}


def _tool(name, description, properties, required=()):
    return {"name": name, "description": description, "eager_input_streaming": True,
            "input_schema": {"type": "object", "properties": properties, "required": list(required)}}


TOOLS = [
    _tool("volume", "Change the computer's sound volume or mute/unmute it.",
          {"action": {"type": "string", "enum": ["up", "down", "mute"]},
           "steps": {"type": "integer", "description": "1 step = 2%. Default 5 (10%)."}}, ["action"]),
    _tool("media", "Control the music/video that is playing (any player, YouTube in the browser).",
          {"action": {"type": "string", "enum": ["play_pause", "next", "previous"]}}, ["action"]),
    _tool("open_folder", "Open one of the user's standard folders in Explorer.",
          {"folder": {"type": "string", "enum": list(FOLDERS)}}, ["folder"]),
    _tool("find_files", "Find files by part of the name in the user's Desktop, Documents, Downloads, "
                        "Pictures, Music and Videos. Returns up to 10 paths, newest first.",
          {"name": {"type": "string"}}, ["name"]),
    _tool("open_file", "Open a file or folder with its default program. Use a path from find_files.",
          {"path": {"type": "string"}}, ["path"]),
    _tool("power", "Lock, sleep, shut down or restart the computer. Shut down and restart happen after "
                   "60 seconds and can be cancelled with action=cancel. Only call shutdown or restart "
                   "after the user clearly confirmed it in this conversation.",
          {"action": {"type": "string", "enum": ["lock", "sleep", "shutdown", "restart", "cancel"]}},
          ["action"]),
    _tool("look_at_screen", "Take a screenshot of the computer screen to see what the user sees. "
                            "Use when the user asks about something on the screen.", {}),
    _tool("remember", "Save a fact about the user for future conversations (name, birthday, "
                      "preferences, people, plans). Use when asked to remember something.",
          {"fact": {"type": "string"}}, ["fact"]),
    _tool("forget", "Delete saved facts that contain this text.",
          {"text": {"type": "string"}}, ["text"]),
]


class PcTools:
    def __init__(self, memory, home: Path | None = None, run=subprocess.run):
        self.memory = memory
        self.home = home or Path.home()
        self.run = run  # подменяется в тестах
        self.windows = sys.platform == "win32"

    # --- вызов из Brain ------------------------------------------------------------
    def execute(self, name: str, args: dict):
        """Возвращает текст или список блоков (для снимка экрана). Ошибки — тоже текстом."""
        handler = getattr(self, f"_{name}", None)
        if handler is None or name not in {t["name"] for t in TOOLS}:
            return f"Unknown tool {name}"
        if not isinstance(args, dict):
            return "Invalid input"
        try:
            return handler(**{k: v for k, v in args.items() if isinstance(k, str)})
        except TypeError as exc:
            return f"Invalid input: {exc}"
        except Exception as exc:
            return f"Failed: {exc}"

    # --- инструменты ---------------------------------------------------------------
    def _press(self, key: str, times: int = 1) -> None:
        if not self.windows:
            raise RuntimeError("works on Windows only")
        import ctypes

        user32 = ctypes.windll.user32
        for _ in range(times):
            user32.keybd_event(KEYS[key], 0, 0, 0)
            user32.keybd_event(KEYS[key], 0, 2, 0)

    def _volume(self, action: str, steps: int = 5) -> str:
        if action not in ("up", "down", "mute"):
            return "Invalid action"
        steps = max(1, min(int(steps or 5), 50))
        key = {"up": "volume_up", "down": "volume_down", "mute": "mute"}[action]
        self._press(key, 1 if action == "mute" else steps)
        return "Done" if action == "mute" else f"Volume {action} by {steps * 2}%"

    def _media(self, action: str) -> str:
        if action not in ("play_pause", "next", "previous"):
            return "Invalid action"
        self._press(action)
        return "Done"

    def _folder(self, folder: str) -> Path:
        if self.windows and folder in CSIDL:
            import ctypes

            buffer = ctypes.create_unicode_buffer(260)
            if ctypes.windll.shell32.SHGetFolderPathW(None, CSIDL[folder], None, 0, buffer) == 0:
                return Path(buffer.value)
        return self.home / FOLDERS[folder]

    def _start(self, path: Path) -> None:
        if self.windows:
            os.startfile(str(path))  # type: ignore[attr-defined]
        else:
            self.run(["xdg-open", str(path)], check=False)

    def _open_folder(self, folder: str) -> str:
        if folder not in FOLDERS:
            return "Unknown folder"
        path = self._folder(folder)
        self._start(path)
        return f"Opened {path}"

    def _search_roots(self) -> list[Path]:
        roots = []
        for folder in FOLDERS:
            path = self._folder(folder)
            if path.is_dir() and path not in roots:
                roots.append(path)
        return roots

    def _find_files(self, name: str) -> str:
        needle = str(name).lower().strip()
        if not needle:
            return "Empty name"
        found = []
        deadline = time.monotonic() + 6  # большие папки не должны надолго подвешивать ответ
        for root in self._search_roots():
            for dirpath, dirnames, filenames in os.walk(root):
                dirnames[:] = [d for d in dirnames if not d.startswith(".") and d != "node_modules"]
                for item in filenames + dirnames:
                    if needle in item.lower():
                        found.append(Path(dirpath) / item)
                if len(found) > 200 or time.monotonic() > deadline:
                    break
        found.sort(key=lambda p: p.stat().st_mtime if p.exists() else 0, reverse=True)
        if not found:
            return "Nothing found"
        return "\n".join(str(p) for p in found[:MAX_FOUND])

    def _open_file(self, path: str) -> str:
        target = Path(path).expanduser()
        if not target.exists():
            return "File not found"
        self._start(target)
        return f"Opened {target}"

    def _power(self, action: str) -> str:
        if not self.windows:
            return "Works on Windows only"
        commands = {
            "shutdown": ["shutdown", "/s", "/t", "60"],
            "restart": ["shutdown", "/r", "/t", "60"],
            "cancel": ["shutdown", "/a"],
            "lock": ["rundll32.exe", "user32.dll,LockWorkStation"],
            "sleep": ["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"],
        }
        if action not in commands:
            return "Invalid action"
        self.run(commands[action], check=False)
        if action in ("shutdown", "restart"):
            return f"{action} in 60 seconds; say 'cancel' to stop it"
        return "Done"

    def _look_at_screen(self):
        if not self.windows:
            return "Works on Windows only"
        fd, path = tempfile.mkstemp(prefix="kartal-screen-", suffix=".jpg")
        os.close(fd)
        try:
            self.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                      SCREENSHOT_SCRIPT.replace("{path}", path)],
                     check=True, capture_output=True, timeout=20,
                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            data = Path(path).read_bytes()
        finally:
            try:
                os.remove(path)
            except OSError:
                pass
        if not data:
            return "Screenshot failed"
        return [{"type": "image", "source": {"type": "base64", "media_type": "image/jpeg",
                                             "data": base64.b64encode(data).decode("ascii")}}]

    def _remember(self, fact: str) -> str:
        self.memory.remember(str(fact))
        return "Saved"

    def _forget(self, text: str) -> str:
        return f"Deleted {self.memory.forget(str(text))} facts"


# Снимок всех мониторов средствами .NET (без дополнительных пакетов), уменьшенный до 1600 px.
SCREENSHOT_SCRIPT = r"""
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -Namespace K -Name Dpi -MemberDefinition '[DllImport("user32.dll")] public static extern bool SetProcessDPIAware();'
[K.Dpi]::SetProcessDPIAware() | Out-Null
$b = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($b.Left, $b.Top, 0, 0, $bmp.Size)
$w = [Math]::Min(1600, $b.Width); $h = [int]($b.Height * $w / $b.Width)
$small = New-Object System.Drawing.Bitmap $bmp, $w, $h
$small.Save('{path}', [System.Drawing.Imaging.ImageFormat]::Jpeg)
"""
