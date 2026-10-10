"""Double-click launcher with a visible startup error and a local log."""
import os
import sys
import traceback
from pathlib import Path

root = Path(__file__).resolve().parent
os.chdir(root)
log_dir = Path(os.environ.get("LOCALAPPDATA", root)) / "Kartal"
log_dir.mkdir(parents=True, exist_ok=True)
log = (log_dir / "startup.log").open("w", encoding="utf-8", buffering=1)
sys.stdout = sys.stderr = log
try:
    from assistant.main import main
    main()
except Exception:
    traceback.print_exc()
    import tkinter as tk
    from tkinter import messagebox
    window = tk.Tk()
    window.withdraw()
    messagebox.showerror("Kartal", "Не удалось запустить Kartal. Подробности в файле:\n" + str(log_dir / "startup.log"))
    window.destroy()
