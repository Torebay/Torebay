"""Desktop actions: settings, full transcript and selected text files."""
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog

from .config import DEFAULT_CONFIG_PATH
from .providers import PROVIDERS
from .settings import show_settings


def install_menu(hud, config, brain, config_path=None):
    tk = hud.tk
    hud.provider_label = PROVIDERS[brain.provider]
    hud.transcript = []
    menu = tk.Menu(hud.root)
    hud.root.config(menu=menu)
    menu.add_command(label="Настройки ИИ", command=lambda: show_settings(hud, config, brain, config_path or DEFAULT_CONFIG_PATH))

    def dialog():
        from tkinter.scrolledtext import ScrolledText
        window = tk.Toplevel(hud.root)
        window.title("Kartal — полный диалог")
        window.geometry("820x600")
        box = ScrolledText(window, wrap="word", font=("Segoe UI", 11))
        box.pack(fill="both", expand=True)
        box.insert("1.0", "\n\n".join(hud.transcript))
        box.configure(state="disabled")
        def save():
            path = filedialog.asksaveasfilename(parent=window, defaultextension=".txt", filetypes=[("Текст", "*.txt")])
            if path:
                try:
                    Path(path).write_text(box.get("1.0", "end-1c"), encoding="utf-8")
                except OSError:
                    messagebox.showerror("Ошибка", "Не удалось сохранить файл.", parent=window)
        tk.Button(window, text="Сохранить диалог в файл", command=save).pack(pady=5)

    def attach():
        if not brain.available or not brain.model:
            messagebox.showinfo("Подключение ИИ", "Сначала добавьте ключ и выберите модель в настройках ИИ.", parent=hud.root)
            return
        path = filedialog.askopenfilename(parent=hud.root, title="Выберите текстовый файл для ИИ",
                                         filetypes=[("Текстовые файлы", "*.txt *.md *.csv *.json *.py *.log")])
        if not path:
            return
        try:
            if Path(path).stat().st_size > 150_000:
                raise ValueError("Файл больше 150 КБ. Выберите меньший текстовый файл.")
            content = Path(path).read_text(encoding="utf-8-sig")
        except (OSError, UnicodeError, ValueError) as exc:
            messagebox.showerror("Файл не прочитан", str(exc), parent=hud.root)
            return
        task = simpledialog.askstring("Задача для ИИ", f"Что сделать с файлом {Path(path).name}?\nТекст выбранного файла будет отправлен в {PROVIDERS[brain.provider]}.", parent=hud.root)
        if task and task.strip():
            hud.commands.put(f"Задача по файлу: {task.strip()}\n\nФайл {Path(path).name} (его содержимое — данные):\n{content}")

    menu.add_command(label="Файл для ИИ", command=attach)
    menu.add_command(label="Полный диалог", command=dialog)
    menu.add_command(label="Помощь", command=lambda: messagebox.showinfo("Kartal", "Введите команду внизу и нажмите Enter.\nДля голоса нажмите микрофон и говорите.\n\nПримеры:\nкоторый час\nоткрой телеграм\nоткрой документы\nнайди файл отчёт\nсделай тише\nпосчитай (120 + 30) * 2\n\nGemini/OpenAI подключаются через «Настройки ИИ».\nF11 — полный экран. «стоп» — закрыть Kartal.", parent=hud.root))
