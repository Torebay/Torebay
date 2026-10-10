"""Settings window for both AI services, keys and available models."""
import json
import queue
import threading
import webbrowser
from dataclasses import asdict
from tkinter import ttk, messagebox

from .config import DEFAULT_CONFIG_PATH
from .providers import PROVIDERS, list_models, ProviderError
from .secrets import get_key, save_keys


def add_clipboard_support(entry, parent):
    """Explicit paste works with both Latin and Cyrillic keyboard layouts."""
    def paste():
        try:
            value = parent.clipboard_get().strip()
        except Exception:
            messagebox.showinfo("Буфер обмена", "Сначала скопируйте API-ключ в кабинете сервиса.", parent=parent)
            return "break"
        if value:
            try:
                entry.delete("sel.first", "sel.last")
            except Exception:
                pass
            entry.insert("insert", value)
            entry.focus_set()
        return "break"

    def key(event):
        if event.keycode == 86 or event.keysym.lower() in ("v", "м"):
            return paste()
        if event.keycode == 65 or event.keysym.lower() in ("a", "ф"):
            entry.selection_range(0, "end")
            return "break"
    entry.bind("<Control-KeyPress>", key)
    entry.bind("<Shift-Insert>", lambda event: paste())
    import tkinter as tk
    popup = tk.Menu(entry, tearoff=False)
    popup.add_command(label="Вставить", command=paste)
    popup.add_command(label="Выделить всё", command=lambda: entry.selection_range(0, "end"))
    popup.add_command(label="Очистить поле", command=lambda: entry.delete(0, "end"))
    entry.bind("<Button-3>", lambda event: popup.tk_popup(event.x_root, event.y_root))
    return paste


def show_settings(hud, config, brain, path=DEFAULT_CONFIG_PATH):
    tk = hud.tk
    old = getattr(hud, "settings_window", None)
    if old is not None and old.winfo_exists():
        old.lift()
        return
    window = tk.Toplevel(hud.root)
    hud.settings_window = window
    window.title("Kartal — настройки ИИ")
    window.geometry("640x650")
    window.minsize(480, 360)
    window.transient(hud.root)
    # Reserve the footer before the scrollable content, including at high DPI.
    footer = ttk.Frame(window, padding=(14, 10))
    footer.pack(side="bottom", fill="x")
    body = ttk.Frame(window)
    body.pack(fill="both", expand=True)
    canvas = tk.Canvas(body, highlightthickness=0)
    scrollbar = ttk.Scrollbar(body, orient="vertical", command=canvas.yview)
    scrollbar.pack(side="right", fill="y")
    canvas.pack(side="left", fill="both", expand=True)
    canvas.configure(yscrollcommand=scrollbar.set)
    frame = ttk.Frame(canvas, padding=14)
    content = canvas.create_window((0, 0), window=frame, anchor="nw")
    frame.bind("<Configure>", lambda event: canvas.configure(scrollregion=canvas.bbox("all")))
    canvas.bind("<Configure>", lambda event: canvas.itemconfigure(content, width=event.width))
    ttk.Label(frame, text="Gemini и OpenAI", font=("Segoe UI", 17, "bold")).pack(anchor="w")
    ttk.Label(frame, text="Можно сохранить оба ключа. Отвечает выбранный сервис.").pack(anchor="w", pady=(5, 15))
    selected = tk.StringVar(value=brain.provider)
    row = ttk.Frame(frame)
    row.pack(fill="x")
    for provider, label in PROVIDERS.items():
        ttk.Radiobutton(row, text=label, variable=selected, value=provider).pack(side="left", padx=(0, 20))
    notebook = ttk.Notebook(frame)
    notebook.pack(fill="x", pady=12)
    keys, models, boxes = {}, {}, {}
    result_queue = queue.Queue()
    status = tk.StringVar()
    for provider, label in PROVIDERS.items():
        tab = ttk.Frame(notebook, padding=14)
        notebook.add(tab, text=label)
        ttk.Label(tab, text="API-ключ").pack(anchor="w")
        keys[provider] = tk.StringVar(value=get_key(provider))
        key_row = ttk.Frame(tab)
        key_row.pack(fill="x", pady=(4, 10))
        key_entry = ttk.Entry(key_row, textvariable=keys[provider], show="•")
        key_entry.pack(side="left", fill="x", expand=True)
        paste = add_clipboard_support(key_entry, window)
        def replace_key(paste=paste, entry=key_entry):
            entry.selection_range(0, "end")
            paste()
        ttk.Button(key_row, text="Вставить", command=replace_key).pack(side="left", padx=(8, 0))
        ttk.Label(tab, text="Модель (из вашего аккаунта или введите название)").pack(anchor="w")
        models[provider] = tk.StringVar(value=config.ai.get(provider + "_model", ""))
        boxes[provider] = ttk.Combobox(tab, textvariable=models[provider])
        boxes[provider].pack(fill="x", pady=4)
        actions = ttk.Frame(tab)
        actions.pack(fill="x", pady=(10, 0))

        def fetch(p=provider):
            key = keys[p].get().strip()
            status.set("Запрашиваю список моделей…")
            def work():
                try:
                    result_queue.put((p, list_models(p, key), None))
                except ProviderError as exc:
                    result_queue.put((p, [], str(exc)))
                except Exception:
                    result_queue.put((p, [], "Не удалось получить список моделей."))
            threading.Thread(target=work, daemon=True).start()

        ttk.Button(actions, text="Загрузить модели", command=fetch).pack(side="left")
        url = "https://platform.openai.com/api-keys" if provider == "openai" else "https://aistudio.google.com/api-keys"
        ttk.Button(actions, text="Получить API-ключ", command=lambda u=url: webbrowser.open(u)).pack(side="left", padx=10)
    search = tk.BooleanVar(value=config.ai.get("web_search", True))
    listen = tk.BooleanVar(value=config.voice.get("always_listen", False))
    ttk.Checkbutton(frame, text="Поиск ИИ в интернете (если поддерживает выбранная модель)", variable=search).pack(anchor="w")
    ttk.Checkbutton(frame, text="Слушать постоянно и откликаться на имя «Картал»", variable=listen).pack(anchor="w", pady=5)
    voice_row = ttk.Frame(frame)
    voice_row.pack(fill="x", pady=8)
    ttk.Label(voice_row, text="Голос:").pack(side="left")
    voice_engine = tk.StringVar(value="OpenAI" if config.voice.get("engine") == "openai" else "Windows")
    ttk.Combobox(voice_row, textvariable=voice_engine, state="readonly", values=["Windows", "OpenAI"], width=12).pack(side="left", padx=8)
    voice_name = tk.StringVar(value=config.voice.get("openai_voice", "cedar"))
    ttk.Combobox(voice_row, textvariable=voice_name, state="readonly", values=["cedar", "marin"], width=10).pack(side="left")
    ttk.Label(frame, text="OpenAI — синтетический нейроголос; нужен ключ OpenAI, расходуется API.\nWindows — установленный голос без API.", wraplength=580).pack(anchor="w")
    ttk.Label(frame, text="Ключи шифруются для вашей учётной записи Windows.\nБез ключей работают локальные команды.", wraplength=560).pack(anchor="w", pady=8)
    ttk.Label(frame, textvariable=status, wraplength=550).pack(anchor="w", pady=8)

    def poll():
        if not window.winfo_exists():
            return
        try:
            while True:
                provider, names, error = result_queue.get_nowait()
                if error:
                    status.set(error)
                else:
                    boxes[provider]["values"] = names
                    status.set(f"{PROVIDERS[provider]}: найдено моделей — {len(names)}. Выберите нужную.")
        except queue.Empty:
            pass
        window.after(150, poll)

    def save():
        settings = {"provider": selected.get(), "web_search": search.get(),
                    **{p + "_model": v.get().strip() for p, v in models.items()}}
        values = asdict(config)
        values["ai"] = settings
        values["voice"]["always_listen"] = listen.get()
        values["voice"]["engine"] = "openai" if voice_engine.get() == "OpenAI" else "windows"
        values["voice"]["openai_voice"] = voice_name.get()
        try:
            save_keys({p: v.get() for p, v in keys.items()})
            from pathlib import Path
            target = Path(path)
            temporary = target.with_suffix(".json.tmp")
            temporary.write_text(json.dumps(values, ensure_ascii=False, indent=2), encoding="utf-8")
            temporary.replace(target)
        except OSError:
            messagebox.showerror("Не удалось сохранить", "Проверьте доступ к папке программы и хранилищу ключей.", parent=window)
            return
        config.ai = settings
        config.voice["always_listen"] = listen.get()
        config.voice.update(values["voice"])
        brain.configure(settings)
        hud.provider_label = PROVIDERS[brain.provider]
        hud.model, hud.has_key, hud.web_search = brain.model, brain.available, brain.web_search
        hud.log("bot", f"Выбран {hud.provider_label}. Настройки сохранены. Для проверки голоса введите «проверь голос».")
        window.destroy()
        hud.root.lift()

    ttk.Button(footer, text="Сохранить и вернуться в Kartal", command=save).pack(side="right")
    window.bind("<Control-Return>", lambda event: save())
    poll()
