"""Анимированное окно в стиле Джарвиса: светящиеся кольца, которые меняются,
когда ассистент ждёт, слушает, думает или говорит.

Окно работает на tkinter (он уже есть в Python для Windows), поэтому ничего
доустанавливать не нужно. Само окно живёт в главном потоке, а ассистент
работает в соседнем и только сообщает окну своё состояние через очередь.
"""

from __future__ import annotations

import math
import queue
import random
import time

# Состояние → (основной цвет, скорость вращения, сила пульса)
STATES = {
    "idle": ("#00b4d8", 0.4, 0.15),
    "listening": ("#00f0ff", 1.0, 0.45),
    "thinking": ("#ffb703", 2.6, 0.25),
    "speaking": ("#4cc9f0", 0.8, 1.0),
}
STATUS_TEXT = {
    "ru": {"idle": "ожидание", "listening": "слушаю", "thinking": "думаю", "speaking": "говорю"},
    "uz": {"idle": "kutish", "listening": "eshitaman", "thinking": "o'ylayapman", "speaking": "gapiryapman"},
    "tr": {"idle": "bekliyor", "listening": "dinliyorum", "thinking": "düşünüyorum", "speaking": "konuşuyorum"},
}
SIZE = 460
BG = "#03080f"
FPS = 40


def _mix(color: str, factor: float) -> str:
    """Делает цвет темнее (factor < 1) или светлее (factor > 1)."""
    r, g, b = (int(color[i:i + 2], 16) for i in (1, 3, 5))
    if factor <= 1:
        r, g, b = (int(c * factor) for c in (r, g, b))
    else:
        k = min(factor - 1, 1)
        r, g, b = (int(c + (255 - c) * k) for c in (r, g, b))
    return f"#{r:02x}{g:02x}{b:02x}"


class HudWindow:
    def __init__(self, name: str, lang: str = "ru", always_on_top: bool = True):
        import tkinter as tk

        self.tk = tk
        self.name = name
        self.lang = lang
        self.state = "idle"
        self.caption = ""
        self.events: queue.Queue = queue.Queue()
        self.angle = 0.0
        self.level = 0.0  # «громкость» для эффекта речи
        self.closed = False

        self.root = tk.Tk()
        self.root.title(name)
        self.root.configure(bg=BG)
        self.root.geometry(f"{SIZE}x{SIZE + 70}")
        self.root.resizable(False, False)
        if always_on_top:
            self.root.attributes("-topmost", True)
        self.root.protocol("WM_DELETE_WINDOW", self.close)

        self.canvas = tk.Canvas(self.root, width=SIZE, height=SIZE + 70, bg=BG, highlightthickness=0)
        self.canvas.pack()

    # --- вызывается из потока ассистента -------------------------------------------
    def set_state(self, state: str) -> None:
        self.events.put(("state", state))

    def set_caption(self, text: str) -> None:
        self.events.put(("caption", text))

    def set_language(self, lang: str) -> None:
        self.events.put(("lang", lang))

    def close(self) -> None:
        self.events.put(("close", None))

    # --- главный поток -------------------------------------------------------------
    def run(self) -> None:
        self._tick()
        self.root.mainloop()

    def _drain_events(self) -> None:
        while True:
            try:
                kind, value = self.events.get_nowait()
            except queue.Empty:
                return
            if kind == "state" and value in STATES:
                self.state = value
            elif kind == "caption":
                self.caption = value
            elif kind == "lang":
                self.lang = value
            elif kind == "close":
                self.closed = True

    def _tick(self) -> None:
        self._drain_events()
        if self.closed:
            self.root.destroy()
            return
        self._draw(time.monotonic())
        self.root.after(1000 // FPS, self._tick)

    def _draw(self, now: float) -> None:
        c = self.canvas
        c.delete("all")
        color, speed, pulse_power = STATES[self.state]
        self.angle = (self.angle + speed * 2.2) % 360
        cx = cy = SIZE / 2

        if self.state == "speaking":
            target = random.uniform(0.35, 1.0)
        elif self.state == "listening":
            target = 0.35 + 0.25 * math.sin(now * 5)
        else:
            target = 0.0
        self.level += (target - self.level) * 0.3
        pulse = (math.sin(now * (2 + speed * 2)) + 1) / 2 * pulse_power + self.level * 0.6

        # Мягкое свечение вокруг ядра.
        for i in range(8, 0, -1):
            r = 70 + i * 9 + pulse * 18
            c.create_oval(cx - r, cy - r, cx + r, cy + r, outline="", fill=_mix(color, 0.03 + 0.012 * (9 - i)))

        # Внешнее кольцо из делений.
        for i in range(72):
            a = math.radians(i * 5 + self.angle * 0.3)
            r1, r2 = 200, 200 - (12 if i % 6 == 0 else 6)
            c.create_line(cx + r1 * math.cos(a), cy + r1 * math.sin(a),
                          cx + r2 * math.cos(a), cy + r2 * math.sin(a),
                          fill=_mix(color, 0.55), width=2)

        # Вращающиеся дуги в разные стороны.
        arcs = [(180, 8, 1.0, 4), (160, 5, -1.6, 3), (138, 3, 2.3, 6)]
        for radius, width, direction, count in arcs:
            step = 360 / count
            for k in range(count):
                start = self.angle * direction + k * step
                c.create_arc(cx - radius, cy - radius, cx + radius, cy + radius, start=start,
                             extent=step * 0.55, style="arc", outline=_mix(color, 0.9), width=width)

        # Звуковая «волна» вокруг ядра, когда ассистент говорит или слушает.
        if self.level > 0.05:
            for i in range(48):
                a = math.radians(i * 7.5)
                h = 6 + self.level * random.uniform(8, 34)
                r1 = 104
                c.create_line(cx + r1 * math.cos(a), cy + r1 * math.sin(a),
                              cx + (r1 + h) * math.cos(a), cy + (r1 + h) * math.sin(a),
                              fill=_mix(color, 1.2), width=3)

        # Ядро.
        core = 62 + pulse * 14
        c.create_oval(cx - core, cy - core, cx + core, cy + core, outline=_mix(color, 1.4), width=3,
                      fill=_mix(color, 0.18))
        inner = core * 0.62
        c.create_oval(cx - inner, cy - inner, cx + inner, cy + inner, outline=_mix(color, 1.1), width=2)

        c.create_text(cx, cy - 8, text=self.name.upper(), fill=_mix(color, 1.6),
                      font=("Segoe UI", 18, "bold"))
        status = STATUS_TEXT.get(self.lang, STATUS_TEXT["ru"])[self.state]
        c.create_text(cx, cy + 20, text=status, fill=_mix(color, 1.0), font=("Segoe UI", 11))

        caption = self.caption if len(self.caption) <= 120 else self.caption[:117] + "..."
        c.create_text(cx, SIZE + 30, text=caption, fill="#cfefff", width=SIZE - 30,
                      font=("Segoe UI", 11), justify="center")


class HudIO:
    """Обёртка над TextIO/VoiceIO: делает то же самое и показывает это в окне."""

    def __init__(self, inner, hud: HudWindow):
        self.inner = inner
        self.hud = hud
        self.name = inner.name
        hud.set_language(inner.lang)

    @property
    def lang(self) -> str:
        return self.inner.lang

    def set_language(self, lang: str) -> None:
        self.inner.set_language(lang)
        self.hud.set_language(self.inner.lang)

    def listen(self) -> str | None:
        self.hud.set_state("listening")
        heard = self.inner.listen()
        self.hud.set_state("thinking" if heard else "idle")
        if heard:
            self.hud.set_caption(heard)
        return heard

    def say(self, text: str) -> None:
        self.hud.set_state("speaking")
        self.hud.set_caption(text)
        try:
            self.inner.say(text)
        finally:
            self.hud.set_state("idle")
