"""Загрузка компьютера для панели: процессор, память, диск, батарея.

Только стандартная библиотека: на Windows числа берутся из системных функций
через ctypes, на Linux — из /proc. Если что-то узнать нельзя, возвращается None.
"""

from __future__ import annotations

import shutil
import sys
import time
from pathlib import Path


class CpuMeter:
    """Процент загрузки процессора между двумя замерами."""

    def __init__(self):
        self._last = self._times()

    def _times(self) -> tuple[int, int] | None:
        """(время простоя, общее время) с момента запуска системы."""
        try:
            if sys.platform == "win32":
                import ctypes
                from ctypes import wintypes

                idle, kernel, user = wintypes.FILETIME(), wintypes.FILETIME(), wintypes.FILETIME()
                if not ctypes.windll.kernel32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel),
                                                             ctypes.byref(user)):
                    return None

                def value(ft):
                    return (ft.dwHighDateTime << 32) | ft.dwLowDateTime

                # Время ядра уже включает простой.
                return value(idle), value(kernel) + value(user)
            fields = [int(x) for x in Path("/proc/stat").read_text().split("\n", 1)[0].split()[1:]]
            return fields[3] + fields[4], sum(fields)
        except Exception:
            return None

    def percent(self) -> float | None:
        now = self._times()
        last, self._last = self._last, now
        if not now or not last or now[1] <= last[1]:
            return None
        idle = now[0] - last[0]
        total = now[1] - last[1]
        return max(0.0, min(100.0, 100.0 * (1 - idle / total)))


def memory_percent() -> float | None:
    try:
        if sys.platform == "win32":
            import ctypes

            class MemoryStatus(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

            status = MemoryStatus()
            status.dwLength = ctypes.sizeof(MemoryStatus)
            if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
                return None
            return float(status.dwMemoryLoad)
        info = {}
        for line in Path("/proc/meminfo").read_text().splitlines():
            key, rest = line.split(":", 1)
            info[key] = int(rest.split()[0])
        return 100.0 * (1 - info["MemAvailable"] / info["MemTotal"])
    except Exception:
        return None


def disk_percent() -> float | None:
    try:
        usage = shutil.disk_usage(Path.home().anchor or "/")
        return 100.0 * usage.used / usage.total
    except Exception:
        return None


def battery() -> tuple[int, bool] | None:
    """(процент заряда, заряжается ли) или None, если батареи нет."""
    try:
        if sys.platform == "win32":
            import ctypes

            class PowerStatus(ctypes.Structure):
                _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte),
                            ("BatteryLifePercent", ctypes.c_ubyte), ("SystemStatusFlag", ctypes.c_ubyte),
                            ("BatteryLifeTime", ctypes.c_ulong), ("BatteryFullLifeTime", ctypes.c_ulong)]

            status = PowerStatus()
            if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(status)):
                return None
            if status.BatteryFlag == 128 or status.BatteryLifePercent == 255:
                return None
            return int(status.BatteryLifePercent), status.ACLineStatus == 1
        for supply in Path("/sys/class/power_supply").glob("BAT*"):
            level = int((supply / "capacity").read_text())
            charging = (supply / "status").read_text().strip() in ("Charging", "Full")
            return level, charging
    except Exception:
        return None
    return None


def uptime_seconds() -> float | None:
    try:
        if sys.platform == "win32":
            import ctypes

            ticks = ctypes.windll.kernel32.GetTickCount64
            ticks.restype = ctypes.c_ulonglong
            return ticks() / 1000
        return float(Path("/proc/uptime").read_text().split()[0])
    except Exception:
        return None


def format_uptime(seconds: float | None) -> str:
    if seconds is None:
        return "—"
    hours, rest = divmod(int(seconds), 3600)
    return f"{hours}:{rest // 60:02d}"


if __name__ == "__main__":
    meter = CpuMeter()
    time.sleep(0.5)
    print(meter.percent(), memory_percent(), disk_percent(), battery(), format_uptime(uptime_seconds()))
