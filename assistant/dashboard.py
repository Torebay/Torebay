"""Большое окно-пульт в стиле Джарвиса: светящийся глобус-ядро и панели вокруг.

Панели настоящие: лента разговора, быстрые команды, строка для набора команд,
загрузка компьютера, цены на бирже, часы и состояние ассистента. Всё рисуется
на одном холсте tkinter, поэтому ничего доустанавливать не нужно.

Окно живёт в главном потоке, а ассистент — в соседнем; они общаются через
очереди, как и маленькое окно в ui.py.
"""

from __future__ import annotations

import datetime
import math
import queue
import random
import sys
import threading
import time

from . import sysinfo
from .i18n import LANGUAGES
from .markets import Markets

BASE_W, BASE_H = 1280, 820
FPS = 30

# Цветовые темы. «blue» — голубая, «green» — тёмно-зелёная: красная, когда думает, и синяя, когда говорит.
# Тема выбирается в config.json: "theme" в разделе "ui".
THEMES = {
    "blue": {
        "colors": {
        "BG": "#020a14",
        "GRID": "#06182a",
        "PANEL": "#041321",
        "BORDER": "#0d3550",
        "ACCENT": "#00d4ff",
        "TEXT": "#cdeffc",
        "DIM": "#5f8ba6",
        "GREEN": "#2ee59d",
        "ORANGE": "#ffb703",
        "RED": "#ff5d73",
        "BAR": "#030f1b",
        "INPUT": "#03101d",
        "CHIP": "#04182a",
        "BUTTON": "#05192b",
        "MIC_BG": "#05223a",
        "HALO1": "#062033",
        "ICON_BG": "#072338",
        "ROW_LINE": "#0a2236",
        "TRACK": "#0a2a40",
        "HALO2": "#0a3550",
        "MIC_ON": "#0a3d5c",
        "HALO3": "#0f5878",
    },
        # Состояние → (цвет, скорость вращения, сила пульса)
        "states": {
            "idle": ("#00b4d8", 0.35, 0.15),
            "listening": ("#00f0ff", 0.9, 0.45),
            "thinking": ("#ffb703", 2.4, 0.25),
            "speaking": ("#4cc9f0", 0.7, 1.0),
        },
    },
    "green": {
        "colors": {
        "BG": "#021406",
        "GRID": "#062a0e",
        "PANEL": "#04210b",
        "BORDER": "#0d501f",
        "BAR": "#031b09",
        "INPUT": "#031d09",
        "CHIP": "#042a0e",
        "BUTTON": "#052b0f",
        "MIC_BG": "#053a14",
        "HALO1": "#063313",
        "ICON_BG": "#073815",
        "ROW_LINE": "#0a3614",
        "TRACK": "#0a4019",
        "HALO2": "#0a501f",
        "MIC_ON": "#0a5c24",
        "HALO3": "#0f7834",
        "ACCENT": "#1ed37a",
        "TEXT": "#d4f7e2",
        "DIM": "#5e9a78",
        "GREEN": "#7dffa8",
        "ORANGE": "#ffb703",
        "RED": "#ff4d4d",
    },
        "states": {
            "idle": ("#17a85c", 0.35, 0.15),
            "listening": ("#22e07c", 0.9, 0.45),
            "thinking": ("#e53935", 2.4, 0.25),
            "speaking": ("#2f9bff", 0.7, 1.0),
        },
    },
}
STATES: dict = {}


def apply_theme(name: str) -> None:
    """Подставляет цвета темы в константы модуля (до создания окна)."""
    theme = THEMES.get(name, THEMES["blue"])
    globals().update(theme["colors"])
    STATES.clear()
    STATES.update(theme["states"])


apply_theme("blue")

UI_TEXT = {
    "ru": {
        "center": "КОМАНДНЫЙ ЦЕНТР", "system": "СИСТЕМА", "online": "В СЕТИ", "offline": "НЕТ СЕТИ",
        "checking": "ПРОВЕРКА",
        "core": "ЯДРО ИИ", "brain": "Claude ИИ", "brain_ok": "подключён", "brain_off": "нет ключа",
        "voice_row": "Голос", "search_row": "Поиск в интернете", "on": "включён", "off": "выключен",
        "lang_row": "Язык", "memory_row": "Память разговора", "replies": "реплик", "model_row": "Модель",
        "voice": "ГОЛОС", "tap": "Нажмите и говорите", "armed": "Говорите, я слушаю…",
        "or_say": "или скажите «{name}»",
        "quick": "БЫСТРЫЕ КОМАНДЫ", "pc": "КОМПЬЮТЕР", "cpu": "Процессор", "ram": "Память", "disk": "Диск",
        "battery": "Батарея", "no_battery": "от сети", "uptime": "Работает",
        "feed": "ЖИВАЯ ЛЕНТА", "you": "Вы", "feed_empty": "Скажите «{name}» или напишите команду внизу.",
        "markets": "БИРЖА", "updated": "обновлено", "loading": "загрузка…", "gold": "Золото",
        "talk": "ГОВОРИТЬ С {name}", "placeholder": "Напишите команду и нажмите Enter",
        "fullscreen": "F11 — весь экран", "states": {"idle": "ожидание", "listening": "слушаю",
                                                      "thinking": "думаю", "speaking": "говорю"},
        "weekdays": ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"],
        "language": "Русский",
    },
    "uz": {
        "center": "BOSHQARUV MARKAZI", "system": "TIZIM", "online": "ONLAYN", "offline": "INTERNET YO'Q",
        "checking": "TEKSHIRUV",
        "core": "SUN'IY INTELLEKT", "brain": "Claude AI", "brain_ok": "ulangan", "brain_off": "kalit yo'q",
        "voice_row": "Ovoz", "search_row": "Internetda qidiruv", "on": "yoqilgan", "off": "o'chirilgan",
        "lang_row": "Til", "memory_row": "Suhbat xotirasi", "replies": "ta gap", "model_row": "Model",
        "voice": "OVOZ", "tap": "Bosing va gapiring", "armed": "Gapiring, eshityapman…",
        "or_say": "yoki «{name}» deng",
        "quick": "TEZKOR BUYRUQLAR", "pc": "KOMPYUTER", "cpu": "Protsessor", "ram": "Xotira", "disk": "Disk",
        "battery": "Batareya", "no_battery": "tarmoqdan", "uptime": "Ishlayapti",
        "feed": "JONLI LENTA", "you": "Siz", "feed_empty": "«{name}» deng yoki pastda buyruq yozing.",
        "markets": "BIRJA", "updated": "yangilandi", "loading": "yuklanmoqda…", "gold": "Oltin",
        "talk": "{name} BILAN GAPLASHISH", "placeholder": "Buyruq yozing va Enter bosing",
        "fullscreen": "F11 — to'liq ekran", "states": {"idle": "kutish", "listening": "eshitaman",
                                                        "thinking": "o'ylayapman", "speaking": "gapiryapman"},
        "weekdays": ["dushanba", "seshanba", "chorshanba", "payshanba", "juma", "shanba", "yakshanba"],
        "language": "O'zbekcha",
    },
    "tr": {
        "center": "KOMUTA MERKEZİ", "system": "SİSTEM", "online": "ÇEVRİMİÇİ", "offline": "BAĞLANTI YOK",
        "checking": "KONTROL",
        "core": "YAPAY ZEKA", "brain": "Claude AI", "brain_ok": "bağlı", "brain_off": "anahtar yok",
        "voice_row": "Ses", "search_row": "İnternette arama", "on": "açık", "off": "kapalı",
        "lang_row": "Dil", "memory_row": "Sohbet hafızası", "replies": "mesaj", "model_row": "Model",
        "voice": "SES", "tap": "Dokun ve konuş", "armed": "Konuşun, dinliyorum…",
        "or_say": "ya da «{name}» deyin",
        "quick": "HIZLI KOMUTLAR", "pc": "BİLGİSAYAR", "cpu": "İşlemci", "ram": "Bellek", "disk": "Disk",
        "battery": "Pil", "no_battery": "prizde", "uptime": "Çalışma",
        "feed": "CANLI AKIŞ", "you": "Siz", "feed_empty": "«{name}» deyin ya da aşağıya komut yazın.",
        "markets": "BORSA", "updated": "güncellendi", "loading": "yükleniyor…", "gold": "Altın",
        "talk": "{name} İLE KONUŞ", "placeholder": "Komut yazın ve Enter'a basın",
        "fullscreen": "F11 — tam ekran", "states": {"idle": "bekliyor", "listening": "dinliyorum",
                                                     "thinking": "düşünüyorum", "speaking": "konuşuyorum"},
        "weekdays": ["pazartesi", "salı", "çarşamba", "perşembe", "cuma", "cumartesi", "pazar"],
        "language": "Türkçe",
    },
}

# Быстрые команды: (значок, подпись, команда) — команда идёт ассистенту, как будто её сказали.
QUICK_COMMANDS = {
    "ru": [("✈", "Telegram", "открой телеграм"), ("✆", "WhatsApp", "открой ватсап"),
           ("▶", "YouTube", "открой ютуб"), ("◍", "Браузер", "открой браузер"),
           ("◷", "Время", "который час"), ("$", "Курс доллара", "какой сейчас курс доллара к суму"),
           ("₿", "Биткоин", "сколько сейчас стоит биткоин"), ("☰", "Новости", "какие главные новости сегодня")],
    "uz": [("✈", "Telegram", "telegramni och"), ("✆", "WhatsApp", "vatsapni och"),
           ("▶", "YouTube", "youtubeni och"), ("◍", "Brauzer", "brauzerni och"),
           ("◷", "Vaqt", "soat necha"), ("$", "Dollar kursi", "bugun dollar kursi qancha"),
           ("₿", "Bitkoin", "bitkoin hozir qancha turadi"), ("☰", "Yangiliklar", "bugungi asosiy yangiliklar qanday")],
    "tr": [("✈", "Telegram", "telegramı aç"), ("✆", "WhatsApp", "whatsappı aç"),
           ("▶", "YouTube", "youtube'u aç"), ("◍", "Tarayıcı", "tarayıcıyı aç"),
           ("◷", "Saat", "saat kaç"), ("$", "Dolar kuru", "dolar kuru şu an ne kadar"),
           ("₿", "Bitcoin", "bitcoin şu an ne kadar"), ("☰", "Haberler", "bugünün önemli haberleri neler")],
}


def mix(color: str, factor: float) -> str:
    """Делает цвет темнее (factor < 1) или светлее (factor > 1)."""
    r, g, b = (int(color[i:i + 2], 16) for i in (1, 3, 5))
    if factor <= 1:
        r, g, b = (int(c * factor) for c in (r, g, b))
    else:
        k = min(factor - 1, 1)
        r, g, b = (int(c + (255 - c) * k) for c in (r, g, b))
    return f"#{r:02x}{g:02x}{b:02x}"


def blend(a: str, b: str, k: float) -> str:
    """Цвет между a и b: k=0 → a, k=1 → b."""
    ra, ga, ba = (int(a[i:i + 2], 16) for i in (1, 3, 5))
    rb, gb, bb = (int(b[i:i + 2], 16) for i in (1, 3, 5))
    return "#%02x%02x%02x" % (int(ra + (rb - ra) * k), int(ga + (gb - ga) * k), int(ba + (bb - ba) * k))


def sphere_points(count: int, seed: int = 7) -> list[tuple[float, float]]:
    """Точки, равномерно разбросанные по сфере: (широта, долгота) в радианах."""
    rnd = random.Random(seed)
    points = []
    for i in range(count):
        lat = math.asin(2 * (i + 0.5) / count - 1)
        lon = i * math.pi * (3 - math.sqrt(5)) + rnd.uniform(-0.05, 0.05)
        points.append((lat, lon))
    return points


class Dashboard:
    def __init__(self, name: str, lang: str = "ru", *, model: str = "", has_key: bool = False,
                 web_search: bool = True, memory=lambda: 0, always_on_top: bool = False,
                 fetch_markets: bool = True, theme: str = "blue"):
        apply_theme(theme)
        if sys.platform == "win32":
            try:  # чёткая картинка на экранах с увеличением 125–150 %
                import ctypes

                ctypes.windll.shcore.SetProcessDpiAwareness(1)
            except Exception:
                pass
        import tkinter as tk

        self.tk = tk
        self.name = name
        self.lang = lang if lang in UI_TEXT else "ru"
        self.model = model
        self.has_key = has_key
        self.web_search = web_search
        self.memory = memory
        self.state = "idle"
        self.events: queue.Queue = queue.Queue()
        self.commands: queue.Queue = queue.Queue()
        self.armed = threading.Event()
        self.feed: list[tuple[str, str, str]] = []  # (время, кто, текст)
        self.angle = 0.0
        self.level = 0.0
        self.closed = False
        self.fullscreen = False

        self.cpu = sysinfo.CpuMeter()
        self.pc = {"cpu": None, "ram": None, "disk": None, "battery": None, "uptime": None}
        self.markets = Markets()
        self.globe_points = sphere_points(90)

        self.root = tk.Tk()
        self.root.title(f"{name} — {UI_TEXT[self.lang]['center'].title()}")
        self.root.configure(bg=BG)
        screen_w, screen_h = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        scale = min(screen_w * 0.92 / BASE_W, screen_h * 0.86 / BASE_H, 1.6)
        width, height = int(BASE_W * scale), int(BASE_H * scale)
        self.root.geometry(f"{width}x{height}+{(screen_w - width) // 2}+{max(0, (screen_h - height) // 3)}")
        self.root.minsize(640, 410)
        if always_on_top:
            self.root.attributes("-topmost", True)
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.bind("<F11>", lambda _e: self._toggle_fullscreen())
        self.root.bind("<Escape>", lambda _e: self._toggle_fullscreen(False))

        self.canvas = tk.Canvas(self.root, bg=BG, highlightthickness=0)
        self.canvas.pack(fill="both", expand=True)
        self.entry = tk.Entry(self.root, bg=INPUT, fg=TEXT, insertbackground=ACCENT, relief="flat",
                              highlightthickness=0, disabledbackground=INPUT)
        self.entry.bind("<Return>", self._submit)
        self.entry.bind("<FocusIn>", lambda _e: self._placeholder(False))
        self.entry.bind("<FocusOut>", lambda _e: self._placeholder(True))
        self.placeholder_on = False
        self.entry_window = None

        self.s, self.ox, self.oy = scale, 0.0, 0.0
        self._size = (0, 0)
        self.canvas.bind("<Configure>", self._on_resize)

        if fetch_markets:
            threading.Thread(target=self._markets_loop, daemon=True).start()

    # --- вызывается из потока ассистента -------------------------------------------
    def set_state(self, state: str) -> None:
        self.events.put(("state", state))

    def set_caption(self, text: str) -> None:
        pass  # подписи показывает лента разговора

    def set_language(self, lang: str) -> None:
        self.events.put(("lang", lang))

    def log(self, who: str, text: str) -> None:
        self.events.put(("feed", (who, text)))

    def take_command(self) -> str | None:
        try:
            return self.commands.get_nowait()
        except queue.Empty:
            return None

    def take_armed(self) -> bool:
        if self.armed.is_set():
            self.armed.clear()
            return True
        return False

    def close(self) -> None:
        self.events.put(("close", None))

    # --- главный поток -------------------------------------------------------------
    def run(self) -> None:
        self._tick()
        self._slow_tick()
        self.root.mainloop()

    def t(self, key: str) -> str:
        value = UI_TEXT[self.lang][key]
        return value.format(name=self.name) if isinstance(value, str) else value

    def _markets_loop(self) -> None:
        while not self.closed:
            self.markets.refresh()
            self.events.put(("redraw", None))
            time.sleep(120 if self.markets.online else 30)

    def _toggle_fullscreen(self, value: bool | None = None) -> None:
        self.fullscreen = (not self.fullscreen) if value is None else value
        self.root.attributes("-fullscreen", self.fullscreen)

    def _submit(self, _event=None) -> None:
        text = self.entry.get().strip()
        if text and not self.placeholder_on:
            self.commands.put(text)
            self.entry.delete(0, "end")

    def _send(self, text: str) -> None:
        self.commands.put(text)

    def _arm(self, _event=None) -> None:
        self.armed.set()
        self._draw_slow()

    def _placeholder(self, show: bool) -> None:
        if show and not self.entry.get():
            self.entry.insert(0, self.t("placeholder"))
            self.entry.configure(fg=DIM)
            self.placeholder_on = True
        elif not show and self.placeholder_on:
            self.entry.delete(0, "end")
            self.entry.configure(fg=TEXT)
            self.placeholder_on = False

    def _drain_events(self) -> bool:
        changed = False
        while True:
            try:
                kind, value = self.events.get_nowait()
            except queue.Empty:
                return changed
            changed = True
            if kind == "state" and value in STATES:
                self.state = value
                if value == "thinking":
                    self.armed.clear()
            elif kind == "lang" and value in UI_TEXT and value != self.lang:
                self.lang = value
                was_placeholder = self.placeholder_on
                self._placeholder(False)
                self._draw_static()
                if was_placeholder:
                    self._placeholder(True)
            elif kind == "feed":
                who, text = value
                text = " ".join(text.split())
                self.feed.insert(0, (datetime.datetime.now().strftime("%H:%M"), who,
                                     text if len(text) <= 150 else text[:147] + "…"))
                del self.feed[40:]
            elif kind == "close":
                self.closed = True

    def _tick(self) -> None:
        if self._drain_events():
            self._draw_slow()
        if self.closed:
            self.root.destroy()
            return
        self._draw_dynamic(time.monotonic())
        self.root.after(1000 // FPS, self._tick)

    def _slow_tick(self) -> None:
        if self.closed:
            return
        self.pc["cpu"] = self.cpu.percent()
        self.pc["ram"] = sysinfo.memory_percent()
        self.pc["disk"] = sysinfo.disk_percent()
        self.pc["battery"] = sysinfo.battery()
        self.pc["uptime"] = sysinfo.uptime_seconds()
        self._draw_slow()
        self.root.after(1000, self._slow_tick)

    # --- геометрия -----------------------------------------------------------------
    def _on_resize(self, event) -> None:
        if (event.width, event.height) == self._size:
            return
        self._size = (event.width, event.height)
        self.s = min(event.width / BASE_W, event.height / BASE_H)
        self.ox = (event.width - BASE_W * self.s) / 2
        self.oy = (event.height - BASE_H * self.s) / 2
        self._draw_static()
        self._draw_slow()

    def X(self, x: float) -> float:
        return self.ox + x * self.s

    def Y(self, y: float) -> float:
        return self.oy + y * self.s

    def font(self, size: float, bold: bool = False, family: str = "Segoe UI"):
        return (family, -max(7, int(size * self.s)), "bold" if bold else "normal")

    def num_font(self, size: float, bold: bool = True):
        return self.font(size, bold, "Bahnschrift" if sys.platform == "win32" else "DejaVu Sans")

    def rrect(self, x1, y1, x2, y2, r=10, tag="static", **kw):
        """Прямоугольник со скруглёнными углами (в базовых координатах)."""
        x1, y1, x2, y2 = self.X(x1), self.Y(y1), self.X(x2), self.Y(y2)
        r = r * self.s
        pts = [x1 + r, y1, x2 - r, y1, x2, y1, x2, y1 + r, x2, y2 - r, x2, y2,
               x2 - r, y2, x1 + r, y2, x1, y2, x1, y2 - r, x1, y1 + r, x1, y1]
        return self.canvas.create_polygon(pts, smooth=True, tags=tag, **kw)

    def text(self, x, y, tag="static", **kw):
        return self.canvas.create_text(self.X(x), self.Y(y), tags=tag, **kw)

    def line(self, *coords, tag="static", **kw):
        pts = [self.X(v) if i % 2 == 0 else self.Y(v) for i, v in enumerate(coords)]
        return self.canvas.create_line(*pts, tags=tag, **kw)

    def oval(self, cx, cy, r, tag="static", **kw):
        return self.canvas.create_oval(self.X(cx - r), self.Y(cy - r), self.X(cx + r), self.Y(cy + r),
                                       tags=tag, **kw)

    def panel(self, x1, y1, x2, y2, title: str | None = None, extra: str | None = None) -> None:
        self.rrect(x1, y1, x2, y2, 12, fill=PANEL, outline=BORDER, width=max(1, int(self.s)))
        corner = 18
        w = max(2, int(2 * self.s))
        self.line(x1 + 6, y1 + corner + 6, x1 + 6, y1 + 6, x1 + corner + 6, y1 + 6, fill=ACCENT, width=w)
        self.line(x2 - 6, y2 - corner - 6, x2 - 6, y2 - 6, x2 - corner - 6, y2 - 6, fill=ACCENT, width=w)
        if title:
            self.oval(x1 + 22, y1 + 24, 3, fill=ACCENT, outline="")
            self.text(x1 + 32, y1 + 24, text=title, anchor="w", fill=ACCENT, font=self.font(12.5, True))
        if extra:
            self.text(x2 - 18, y1 + 24, text=extra, anchor="e", fill=DIM, font=self.font(10.5))

    # --- статичный слой: фон, рамки панелей, кнопки --------------------------------
    def _draw_static(self) -> None:
        c = self.canvas
        c.delete("all")
        width, height = self._size
        for gx in range(0, int(width) + 1, max(8, int(40 * self.s))):
            c.create_line(gx, 0, gx, height, fill=GRID, tags="static")
        for gy in range(0, int(height) + 1, max(8, int(40 * self.s))):
            c.create_line(0, gy, width, gy, fill=GRID, tags="static")

        # Верхняя полоса.
        self.rrect(10, 8, 1270, 60, 12, fill=BAR, outline=BORDER)
        cx, cy, r = 38, 34, 15
        hexagon = []
        for k in range(6):
            a = math.radians(60 * k + 30)
            hexagon += [cx + r * math.cos(a), cy + r * math.sin(a)]
        self.line(*hexagon, *hexagon[:2], fill=ACCENT, width=max(2, int(2 * self.s)))
        self.oval(cx, cy, 5, fill=ACCENT, outline="")
        self.text(62, 27, text=self.name.upper(), anchor="w", fill=TEXT, font=self.font(20, True))
        self.text(63, 47, text=self.t("center"), anchor="w", fill=DIM, font=self.font(9.5))
        self.rrect(260, 18, 470, 50, 8, fill=CHIP, outline=BORDER)
        self.text(276, 34, text=self.t("system"), anchor="w", fill=DIM, font=self.font(10.5, True))

        # Левая колонка, центр, правая колонка.
        self.panel(16, 72, 300, 340, self.t("core"))
        self.panel(16, 352, 300, 744, self.t("voice"))
        self.panel(312, 72, 968, 470)
        self.panel(312, 482, 636, 744, self.t("quick"))
        self.panel(648, 482, 968, 744, self.t("pc"))
        self.panel(980, 72, 1264, 470, self.t("feed"))
        self.panel(980, 482, 1264, 744, self.t("markets"))

        # Быстрые команды — кнопки.
        for i, (icon, label, command) in enumerate(QUICK_COMMANDS[self.lang]):
            col, row = i % 2, i // 2
            x1, y1 = 330 + col * 150, 520 + row * 54
            tag = f"qc{i}"
            box = self.rrect(x1, y1, x1 + 140, y1 + 44, 8, tag=("static", tag), fill=BUTTON, outline=BORDER)
            self.rrect(x1 + 8, y1 + 8, x1 + 36, y1 + 36, 6, tag=("static", tag), fill=ICON_BG, outline="")
            self.text(x1 + 22, y1 + 22, tag=("static", tag), text=icon, fill=ACCENT, font=self.font(14, True))
            self.text(x1 + 46, y1 + 22, tag=("static", tag), text=label, anchor="w", fill=TEXT,
                      font=self.font(12))
            c.tag_bind(tag, "<Button-1>", lambda _e, cmd=command: self._send(cmd))
            c.tag_bind(tag, "<Enter>", lambda _e, b=box: (c.itemconfigure(b, outline=ACCENT),
                                                          c.configure(cursor="hand2")))
            c.tag_bind(tag, "<Leave>", lambda _e, b=box: (c.itemconfigure(b, outline=BORDER),
                                                          c.configure(cursor="")))

        # Нижняя полоса со строкой ввода.
        self.rrect(16, 758, 300, 810, 12, fill=BAR, outline=BORDER)
        self.rrect(980, 758, 1264, 810, 12, fill=BAR, outline=BORDER)
        self.text(1122, 784, text=self.t("fullscreen"), fill=DIM, font=self.font(11))
        for k, color in enumerate((HALO1, HALO2, HALO3)):
            pad = 6 - k * 2
            self.rrect(380 - pad, 756 - pad, 900 + pad, 812 + pad, 28, fill="" if k < 2 else INPUT,
                       outline=color, width=max(1, int(2 * self.s)))
        self.rrect(380, 756, 900, 812, 28, fill=INPUT, outline=ACCENT, width=max(1, int(2 * self.s)))
        self.text(640, 770, text=self.t("talk").upper(), fill=ACCENT, font=self.font(11, True))
        self.oval(410, 784, 16, tag=("static", "mic2"), fill=MIC_BG, outline=ACCENT)
        self._mic_glyph(410, 784, 0.55, ("static", "mic2"), ACCENT)
        c.tag_bind("mic2", "<Button-1>", self._arm)

        self.entry.configure(font=self.font(13))
        if self.entry_window is not None:
            c.delete(self.entry_window)
        self.entry_window = c.create_window(self.X(436), self.Y(794), window=self.entry, anchor="w",
                                            width=self.s * 440, height=self.s * 24, tags="static")
        if not self.entry.get() and self.root.focus_get() is not self.entry:
            self._placeholder(True)

        c.tag_bind("mic", "<Button-1>", self._arm)
        c.tag_bind("mic", "<Enter>", lambda _e: c.configure(cursor="hand2"))
        c.tag_bind("mic", "<Leave>", lambda _e: c.configure(cursor=""))

    def _mic_glyph(self, cx, cy, k, tag, color) -> None:
        """Значок микрофона из линий: капсула, дужка и ножка."""
        w = max(2, int(3 * k * self.s))
        self.rrect(cx - 7 * k * 1.6, cy - 16 * k * 1.6, cx + 7 * k * 1.6, cy + 4 * k * 1.6, 7 * k * 1.6,
                   tag=tag, fill=color, outline="")
        self.canvas.create_arc(self.X(cx - 12 * k * 1.6), self.Y(cy - 10 * k * 1.6), self.X(cx + 12 * k * 1.6),
                               self.Y(cy + 10 * k * 1.6), start=200, extent=140, style="arc", outline=color,
                               width=w, tags=tag)
        self.line(cx, cy + 10 * k * 1.6, cx, cy + 16 * k * 1.6, tag=tag, fill=color, width=w)

    # --- медленный слой: часы, ленты, цифры (раз в секунду или по событию) ---------
    def _draw_slow(self) -> None:
        if not self._size[0]:
            return
        c = self.canvas
        c.delete("slow")
        now = datetime.datetime.now()
        texts = UI_TEXT[self.lang]

        # Часы и дата.
        months = LANGUAGES[self.lang]["months"]
        date = f"{texts['weekdays'][now.weekday()].capitalize()}, {now.day} {months[now.month - 1]} {now.year}"
        self.text(640, 22, tag="slow", text=date, fill=DIM, font=self.font(11))
        self.text(640, 44, tag="slow", text=now.strftime("%H:%M:%S"), fill=TEXT, font=self.num_font(24))

        # Сеть.
        online = self.markets.online
        status, color = ((self.t("checking"), ORANGE) if online is None
                         else (self.t("online"), GREEN) if online else (self.t("offline"), RED))
        self.oval(370, 34, 4, tag="slow", fill=color, outline="")
        self.text(380, 34, tag="slow", text=status, anchor="w", fill=color, font=self.font(10.5, True))

        # Язык и модель справа сверху.
        self.rrect(1020, 18, 1086, 50, 8, tag="slow", fill=CHIP, outline=BORDER)
        self.text(1053, 34, tag="slow", text=self.lang.upper(), fill=ACCENT, font=self.font(12, True))
        self.rrect(1098, 18, 1258, 50, 8, tag="slow", fill=CHIP, outline=BORDER)
        self.oval(1114, 34, 4, tag="slow", fill=GREEN if self.has_key else RED, outline="")
        self.text(1124, 34, tag="slow", text="Claude AI", anchor="w", fill=TEXT, font=self.font(12, True))

        self._draw_core_panel(texts)
        self._draw_voice_texts(texts)
        self._draw_pc_panel(texts)
        self._draw_feed(texts)
        self._draw_markets(texts)

        # Внизу слева: язык и состояние.
        self.oval(36, 784, 4, tag="slow", fill=STATES[self.state][0], outline="")
        self.text(48, 784, tag="slow", text=f"{self.name}: {texts['states'][self.state]}", anchor="w",
                  fill=TEXT, font=self.font(12))
        c.tag_raise("slow")

    def _draw_core_panel(self, texts) -> None:
        rows = [
            ("brain", texts["brain_ok"] if self.has_key else texts["brain_off"], GREEN if self.has_key else RED),
            ("voice_row", texts["states"][self.state], STATES[self.state][0]),
            ("search_row", texts["on"] if self.web_search else texts["off"], GREEN if self.web_search else DIM),
            ("lang_row", texts["language"], ACCENT),
            ("memory_row", f"{self.memory()} {texts['replies']}", ACCENT),
            ("model_row", self.model or "—", DIM),
        ]
        for i, (key, value, color) in enumerate(rows):
            y = 70 + 52 + i * 36
            self.rrect(30, y - 14, 58, y + 14, 7, tag="slow", fill=ICON_BG, outline="")
            self.oval(44, y, 6, tag="slow", fill="", outline=color, width=max(1, int(2 * self.s)))
            self.text(68, y - 6, tag="slow", text=texts[key], anchor="w", fill=TEXT, font=self.font(12))
            self.text(68, y + 9, tag="slow", text=value, anchor="w", fill=color, font=self.font(10.5))
            self.oval(284, y, 3.5, tag="slow", fill=color, outline="")

    def _draw_voice_texts(self, texts) -> None:
        color = STATES[self.state][0]
        self.text(158, 536, tag="slow", text=texts["states"][self.state].upper(), fill=color,
                  font=self.font(15, True))
        armed = self.armed.is_set()
        self.oval(158, 622, 44, tag=("slow", "mic"), fill=MIC_BG if not armed else MIC_ON,
                  outline=ACCENT, width=max(2, int(2 * self.s)))
        self._mic_glyph(158, 620, 1.0, ("slow", "mic"), ACCENT if not armed else "#ffffff")
        self.text(158, 690, tag="slow", text=texts["armed"] if armed else texts["tap"], fill=TEXT,
                  font=self.font(12, True))
        self.text(158, 712, tag="slow", text=self.t("or_say"), fill=DIM, font=self.font(11))

    def _draw_pc_panel(self, texts) -> None:
        gauges = [("cpu", self.pc["cpu"]), ("ram", self.pc["ram"]), ("disk", self.pc["disk"])]
        for i, (key, value) in enumerate(gauges):
            cx, cy, r = 704 + i * 104, 590, 38
            width = max(3, int(7 * self.s))
            self.canvas.create_oval(self.X(cx - r), self.Y(cy - r), self.X(cx + r), self.Y(cy + r),
                                    outline=TRACK, width=width, tags="slow")
            if value is not None:
                color = GREEN if value < 60 else ORANGE if value < 85 else RED
                color = ACCENT if value < 60 else color
                self.canvas.create_arc(self.X(cx - r), self.Y(cy - r), self.X(cx + r), self.Y(cy + r), start=90,
                                       extent=-3.6 * max(1.0, value), style="arc", outline=color, width=width,
                                       tags="slow")
            self.text(cx, cy, tag="slow", text="—" if value is None else f"{value:.0f}%", fill=TEXT,
                      font=self.num_font(16))
            self.text(cx, cy + r + 18, tag="slow", text=texts[key], fill=DIM, font=self.font(11))

        battery = self.pc["battery"]
        if battery:
            level, charging = battery
            value = f"{level}%" + (" ⚡" if charging else "")
        else:
            value = texts["no_battery"]
        self.text(670, 694, tag="slow", text=texts["battery"], anchor="w", fill=DIM, font=self.font(11))
        self.text(670, 716, tag="slow", text=value, anchor="w", fill=TEXT, font=self.num_font(14))
        self.text(948, 694, tag="slow", text=texts["uptime"], anchor="e", fill=DIM, font=self.font(11))
        self.text(948, 716, tag="slow", text=sysinfo.format_uptime(self.pc["uptime"]), anchor="e", fill=TEXT,
                  font=self.num_font(14))
        if battery:
            self.rrect(740, 702, 880, 714, 5, tag="slow", fill=TRACK, outline="")
            self.rrect(740, 702, 740 + max(12, 140 * battery[0] / 100), 714, 5, tag="slow",
                       fill=GREEN if battery[0] > 25 else ORANGE, outline="")

    def _draw_feed(self, texts) -> None:
        if not self.feed:
            self.text(1122, 270, tag="slow", text=self.t("feed_empty"), fill=DIM, width=220 * self.s,
                      justify="center", font=self.font(12))
            return
        y, bottom = 50 + 72, 456
        for when, who, message in self.feed:
            if y > bottom - 30:
                break
            color = ACCENT if who == "user" else GREEN
            label = texts["you"] if who == "user" else self.name
            self.rrect(994, y - 2, 1022, y + 26, 7, tag="slow", fill=ICON_BG, outline="")
            self.text(1008, y + 12, tag="slow", text=label[:1].upper(), fill=color, font=self.font(13, True))
            self.text(1032, y + 3, tag="slow", text=label, anchor="w", fill=color, font=self.font(11, True))
            self.text(1250, y + 3, tag="slow", text=when, anchor="e", fill=DIM, font=self.font(10))
            item = self.text(1032, y + 14, tag="slow", text=message, anchor="nw", fill=TEXT,
                             width=216 * self.s, font=self.font(11.5))
            box = self.canvas.bbox(item)
            height = (box[3] - box[1]) / self.s if box else 16
            if y + 14 + height > bottom:
                self.canvas.delete(item)
                break
            y += 14 + height + 14

    def _draw_markets(self, texts) -> None:
        if self.markets.updated:
            extra = f"{texts['updated']} {datetime.datetime.fromtimestamp(self.markets.updated):%H:%M}"
        else:
            extra = texts["loading"] if self.markets.online is None else self.t("offline").lower()
        self.text(1246, 506, tag="slow", text=extra, anchor="e", fill=DIM, font=self.font(10))
        for i, quote in enumerate(self.markets.quotes):
            y = 528 + i * 30
            label = texts["gold"] if quote.label == "Золото" else quote.label
            self.text(998, y, tag="slow", text=label, anchor="w", fill=TEXT, font=self.font(12))
            self.text(1176, y, tag="slow", text=quote.text, anchor="e", fill=TEXT, font=self.num_font(12.5))
            if quote.change is not None:
                up = quote.change >= 0
                self.text(1248, y, tag="slow", text=f"{'▲' if up else '▼'} {abs(quote.change):.1f}%", anchor="e",
                          fill=GREEN if up else RED, font=self.num_font(10.5, False))
            if i < len(self.markets.quotes) - 1:
                self.line(996, y + 15, 1248, y + 15, tag="slow", fill=ROW_LINE)

    # --- быстрый слой: глобус и волна (каждый кадр) ---------------------------------
    def _draw_dynamic(self, now: float) -> None:
        if not self._size[0]:
            return
        c = self.canvas
        c.delete("dyn")
        color, speed, pulse_power = STATES[self.state]
        self.angle = (self.angle + speed * 1.6) % 360
        if self.state == "speaking":
            target = random.uniform(0.35, 1.0)
        elif self.state == "listening":
            target = 0.35 + 0.25 * math.sin(now * 5)
        else:
            target = 0.0
        self.level += (target - self.level) * 0.3
        pulse = (math.sin(now * (2 + speed * 2)) + 1) / 2 * pulse_power + self.level * 0.5

        cx, cy, R = 640, 262, 118 + pulse * 4
        # Свечение.
        for i in range(10, 0, -1):
            self.oval(cx, cy, R + i * 7, tag="dyn", fill=blend(PANEL, color, 0.018 * (11 - i)), outline="")
        self.oval(cx, cy, R, tag="dyn", fill=blend(PANEL, color, 0.12), outline="")

        # Внешнее кольцо из делений и вращающиеся дуги.
        for i in range(60):
            a = math.radians(i * 6 + self.angle * 0.25)
            r1, r2 = 178, 178 - (11 if i % 5 == 0 else 5)
            self.line(cx + r1 * math.cos(a), cy + r1 * math.sin(a), cx + r2 * math.cos(a), cy + r2 * math.sin(a),
                      tag="dyn", fill=blend(PANEL, color, 0.55), width=max(1, int(2 * self.s)))
        for radius, width, direction, count in ((160, 4, 1.0, 3), (146, 2, -1.5, 5)):
            step = 360 / count
            for k in range(count):
                c.create_arc(self.X(cx - radius), self.Y(cy - radius), self.X(cx + radius), self.Y(cy + radius),
                             start=self.angle * direction + k * step, extent=step * 0.5, style="arc",
                             outline=blend(PANEL, color, 0.85), width=max(1, int(width * self.s)), tags="dyn")

        # Сетка глобуса: широты и меридианы, задняя сторона тусклее.
        tilt = math.radians(18)
        spin = math.radians(self.angle * 0.8)
        front, back = blend(PANEL, color, 0.75), blend(PANEL, color, 0.25)

        def project(lat, lon):
            x = math.cos(lat) * math.sin(lon + spin)
            y = math.sin(lat)
            z = math.cos(lat) * math.cos(lon + spin)
            y2 = y * math.cos(tilt) - z * math.sin(tilt)
            z2 = y * math.sin(tilt) + z * math.cos(tilt)
            return cx + R * x, cy - R * y2, z2

        def draw_curve(points):
            segment, side = [], None
            for x, y, z in points:
                now_side = z >= 0
                if side is not None and now_side != side and len(segment) >= 2:
                    self.line(*[v for p in segment for v in p], tag="dyn", fill=front if side else back,
                              width=max(1, int(self.s)))
                    segment = segment[-1:]
                segment.append((x, y))
                side = now_side
            if len(segment) >= 2:
                self.line(*[v for p in segment for v in p], tag="dyn", fill=front if side else back,
                          width=max(1, int(self.s)))

        for lat_deg in range(-60, 61, 30):
            lat = math.radians(lat_deg)
            draw_curve([project(lat, math.radians(d)) for d in range(0, 361, 10)])
        for lon_deg in range(0, 180, 30):
            lon = math.radians(lon_deg)
            draw_curve([project(math.radians(d), lon) for d in range(-90, 271, 10)])

        # Точки-«города» на глобусе.
        for lat, lon in self.globe_points:
            x, y, z = project(lat, lon)
            if z > -0.2:
                size = 1.2 + 1.6 * max(0.0, z)
                self.oval(x, y, size, tag="dyn", fill=blend(PANEL, mix(color, 1.4), 0.35 + 0.65 * max(0.0, z)),
                          outline="")

        # Орбиты с бегущими огоньками.
        for k, (rx, ry, rot, phase_speed) in enumerate(((168, 46, -20, 0.9), (150, 34, 25, -1.3))):
            rot_r = math.radians(rot)
            pts = []
            for d in range(0, 361, 8):
                a = math.radians(d)
                px, py = rx * math.cos(a), ry * math.sin(a)
                pts += [cx + px * math.cos(rot_r) - py * math.sin(rot_r), cy + px * math.sin(rot_r) + py * math.cos(rot_r)]
            self.line(*pts, tag="dyn", fill=blend(PANEL, color, 0.45), width=max(1, int(self.s)))
            a = now * phase_speed + k * 2
            px, py = rx * math.cos(a), ry * math.sin(a)
            dot_x = cx + px * math.cos(rot_r) - py * math.sin(rot_r)
            dot_y = cy + px * math.sin(rot_r) + py * math.cos(rot_r)
            self.oval(dot_x, dot_y, 7, tag="dyn", fill=blend(PANEL, color, 0.35), outline="")
            self.oval(dot_x, dot_y, 3.5, tag="dyn", fill=mix(color, 1.5), outline="")

        # Волна вокруг глобуса, когда ассистент слушает или говорит.
        if self.level > 0.05:
            for i in range(60):
                a = math.radians(i * 6)
                h = 4 + self.level * random.uniform(6, 22)
                r1 = R + 6
                self.line(cx + r1 * math.cos(a), cy + r1 * math.sin(a), cx + (r1 + h) * math.cos(a),
                          cy + (r1 + h) * math.sin(a), tag="dyn", fill=mix(color, 1.2), width=max(1, int(2 * self.s)))

        # Надпись поверх глобуса.
        self.text(cx + 2, cy - 6, tag="dyn", text=self.name.upper(), fill=blend(BG, color, 0.6),
                  font=self.font(44, True))
        self.text(cx, cy - 8, tag="dyn", text=self.name.upper(), fill=mix(color, 1.55), font=self.font(44, True))
        self.text(cx, cy + 30, tag="dyn", text="A I   C O R E", fill=mix(color, 1.2), font=self.font(13, True))
        self.text(cx, cy + 50, tag="dyn", text="v2.0", fill=blend(PANEL, color, 0.7), font=self.font(10))
        self.text(cx, 452, tag="dyn", text=UI_TEXT[self.lang]["states"][self.state].upper(), fill=mix(color, 1.2),
                  font=self.font(13, True))

        # Полоски голоса в левой панели.
        for i in range(31):
            x = 48 + i * 7.3
            wave = abs(math.sin(now * 3 + i * 0.45)) * 0.25 + 0.08
            h = 6 + (wave + self.level * random.uniform(0.3, 1.0)) * 70
            fade = 1 - abs(i - 15) / 17
            self.line(x, 462 - h / 2, x, 462 + h / 2, tag="dyn", fill=blend(PANEL, color, 0.35 + 0.6 * fade),
                      width=max(2, int(3 * self.s)), capstyle="round")
        c.tag_lower("dyn", "slow")
        c.tag_raise("dyn", "static")
