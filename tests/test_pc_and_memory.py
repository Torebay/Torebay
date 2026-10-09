from types import SimpleNamespace as NS

from assistant.brain import Brain
from assistant.memory import Memory
from assistant.pc_tools import TOOLS, PcTools
from test_streaming import FakeStream, make_brain


def test_memory_survives_restart(tmp_path):
    path = tmp_path / "memory.json"
    memory = Memory(path)
    memory.history += [{"role": "user", "content": "привет"}, {"role": "assistant", "content": "Салам!"}]
    memory.remember("День рождения Тимы 5 мая")
    again = Memory(path)
    assert again.history[-1]["content"] == "Салам!"
    assert again.facts == ["День рождения Тимы 5 мая"]
    assert again.forget("рождения") == 1 and Memory(path).facts == []


def test_broken_memory_file_is_ignored(tmp_path):
    path = tmp_path / "memory.json"
    path.write_text("{oops", encoding="utf-8")
    assert Memory(path).history == []


def test_facts_go_into_system_prompt():
    memory = Memory(None)
    memory.remember("Любимая команда Тимы — Пахтакор")
    assert "Пахтакор" in Brain("Kartal", {}, memory=memory).system_prompt("ru")


def test_find_and_open_files(tmp_path):
    (tmp_path / "Documents" / "work").mkdir(parents=True)
    (tmp_path / "Documents" / "work" / "Отчёт за май.docx").write_text("x")
    (tmp_path / "Downloads").mkdir()
    (tmp_path / "Downloads" / "photo.jpg").write_text("x")
    calls = []
    tools = PcTools(Memory(None), home=tmp_path, run=lambda cmd, **kw: calls.append(cmd))
    tools.windows = False
    found = tools.execute("find_files", {"name": "отчёт"})
    assert found.endswith("Отчёт за май.docx")
    assert tools.execute("open_file", {"path": found}).startswith("Opened")
    assert calls[-1][0] == "xdg-open"
    assert tools.execute("find_files", {"name": "нет такого"}) == "Nothing found"
    assert tools.execute("open_file", {"path": str(tmp_path / "nope.txt")}) == "File not found"


def test_power_uses_delayed_shutdown_that_can_be_cancelled():
    calls = []
    tools = PcTools(Memory(None), run=lambda cmd, **kw: calls.append(cmd))
    tools.windows = True
    assert "60 seconds" in tools.execute("power", {"action": "shutdown"})
    assert calls[-1] == ["shutdown", "/s", "/t", "60"]
    tools.execute("power", {"action": "cancel"})
    assert calls[-1] == ["shutdown", "/a"]
    assert tools.execute("power", {"action": "format_disk"}) == "Invalid action"


def test_bad_tool_input_does_not_crash():
    tools = PcTools(Memory(None))
    assert tools.execute("power", {}).startswith("Invalid input")
    assert tools.execute("rm_rf", {}) == "Unknown tool rm_rf"
    assert tools.execute("remember", "not a dict") == "Invalid input"
    assert all(t["input_schema"]["type"] == "object" for t in TOOLS)


class ToolStream(FakeStream):
    """Ответ модели, который вызывает инструмент."""

    def get_final_message(self):
        return NS(stop_reason="tool_use", content=[
            NS(type="text", text="Сейчас запомню."),
            NS(type="tool_use", id="tu_1", name="remember", input={"fact": "Тима любит плов"}),
        ])


def test_brain_runs_tools_and_saves_history(monkeypatch, tmp_path):
    brain, calls = make_brain(monkeypatch, ToolStream(["Сейчас запомню."]), FakeStream(["Запомнил!"]))
    memory = Memory(tmp_path / "memory.json")
    brain.memory = memory
    brain.tools = PcTools(memory)
    heard = []
    assert brain.ask("запомни, что я люблю плов", "ru", on_sentence=heard.append) == "Сейчас запомню. Запомнил!"
    assert heard == ["Сейчас запомню.", "Запомнил!"]
    assert memory.facts == ["Тима любит плов"]
    result = calls[1]["messages"][-1]["content"][0]
    assert result == {"type": "tool_result", "tool_use_id": "tu_1", "content": "Saved"}
    assert any(t.get("name") == "look_at_screen" for t in calls[0]["tools"])
    assert Memory(tmp_path / "memory.json").history[-1]["content"] == "Сейчас запомню. Запомнил!"
