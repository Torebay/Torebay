import importlib.util
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "tablet_view", Path(__file__).resolve().parent.parent / "booster" / "tools" / "tablet_view.py"
)
tv = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tv)


@pytest.fixture(autouse=True)
def fake_adb_binary(monkeypatch):
    """Настоящего adb в тестах нет: делаем вид, что он найден."""
    monkeypatch.setattr(tv, "find_adb", lambda explicit=None: "fake-adb")


class FakeAdb:
    """Подставка вместо настоящего adb: отвечает по таблице и записывает вызовы."""

    def __init__(self, devices="List of devices attached\nABC123\tdevice\n", wm="Physical size: 1080x2400\n"):
        self.devices = devices
        self.wm = wm
        self.calls = []
        self.fail_set = False

    def __call__(self, adb, args):
        self.calls.append(args)
        if args == ["devices"]:
            return 0, self.devices
        if args[:3] == ["-s", "ABC123", "shell"]:
            shell = args[3:]
            if shell == ["wm", "size"]:
                return 0, self.wm
            if shell[:2] == ["wm", "size"] and len(shell) == 3:
                return (1, "denied") if self.fail_set else (0, "")
        return 1, "unexpected"


def run(argv, adb=None, reads=()):
    adb = adb or FakeAdb()
    out = []
    answers = iter(reads)
    code = tv.main(argv, runner=adb, out=out.append, read=lambda _p: next(answers))
    return code, "\n".join(out), adb


def test_parse_wm_size_physical_and_override():
    assert tv.parse_wm_size("Physical size: 1080x2400\nOverride size: 1080x1440\n") == ((1080, 2400), (1080, 1440))
    assert tv.parse_wm_size("Physical size: 1080x2400\n") == ((1080, 2400), None)
    assert tv.parse_wm_size("garbage") == (None, None)


def test_parse_devices_skips_noise():
    text = "* daemon not running; starting now\n* daemon started successfully\nList of devices attached\nABC\tdevice\nXYZ\tunauthorized\n\n"
    assert tv.parse_devices(text) == [("ABC", "device"), ("XYZ", "unauthorized")]
    assert tv.parse_devices("List of devices attached\n\n") == []


def test_target_size_4_3_on_tall_phone():
    assert tv.target_size((1080, 2400), (4, 3)) == (1080, 1440)
    assert tv.target_size((1080, 2340), (4, 3)) == (1080, 1440)
    assert tv.target_size((1440, 3200), (3, 2)) == (1440, 2160)
    assert tv.target_size((1080, 2400), (16, 10)) == (1080, 1728)


def test_target_size_keeps_orientation_and_even_numbers():
    assert tv.target_size((2400, 1080), (4, 3)) == (1440, 1080)  # экран, у которого «естественный» вид горизонтальный
    w, h = tv.target_size((1080, 2401), (4, 3))
    assert h % 2 == 0


def test_target_size_none_when_already_squarer():
    assert tv.target_size((1536, 2048), (4, 3)) is None  # iPad-подобный экран
    assert tv.target_size((1080, 1440), (4, 3)) is None
    assert tv.target_size((1080, 1200), (4, 3)) is None


def test_parse_ratio():
    assert tv.parse_ratio("4:3") == (4, 3)
    assert tv.parse_ratio("5:4") == (5, 4)
    for bad in ("", "4x3", "4:0", "abc"):
        try:
            tv.parse_ratio(bad)
        except ValueError:
            continue
        raise AssertionError(f"{bad!r} должен быть отвергнут")


def test_on_sets_size_and_explains_revert():
    code, text, adb = run(["on"])
    assert code == 0
    assert ["-s", "ABC123", "shell", "wm", "size", "1080x1440"] in adb.calls
    assert "1080x1440" in text and "tablet_view.py off" in text


def test_on_with_custom_ratio():
    code, _, adb = run(["on", "--ratio", "3:2"])
    assert code == 0
    assert ["-s", "ABC123", "shell", "wm", "size", "1080x1620"] in adb.calls


def test_on_bad_ratio_changes_nothing():
    code, text, adb = run(["on", "--ratio", "oops"])
    assert code == 1
    assert not any(c[-2:-1] == ["size"] and len(c) == 6 for c in adb.calls)
    assert "oops" in text


def test_on_does_nothing_when_screen_already_square_enough():
    adb = FakeAdb(wm="Physical size: 1536x2048\n")
    code, text, adb = run(["on"], adb)
    assert code == 0
    assert not any(c[-1].count("x") == 1 and c[-2:-1] == ["size"] for c in adb.calls)
    assert "менять нечего" in text


def test_on_reports_phone_refusal():
    adb = FakeAdb()
    adb.fail_set = True
    code, text, _ = run(["on"], adb)
    assert code == 1 and "отказал" in text


def test_off_resets():
    code, text, adb = run(["off"])
    assert code == 0
    assert ["-s", "ABC123", "shell", "wm", "size", "reset"] in adb.calls


def test_status_shows_override():
    code, text, _ = run(["status"], FakeAdb(wm="Physical size: 1080x2400\nOverride size: 1080x1440\n"))
    assert code == 0 and "1080x2400" in text and "1080x1440" in text
    code, text, _ = run(["status"])
    assert "по умолчанию" in text


def test_no_device_explains_setup():
    code, text, _ = run(["on"], FakeAdb(devices="List of devices attached\n"))
    assert code == 1 and "Отладк" in text


def test_unauthorized_device_asks_to_allow():
    code, text, _ = run(["on"], FakeAdb(devices="List of devices attached\nABC123\tunauthorized\n"))
    assert code == 1 and "Разреши" in text


def test_several_devices_require_serial():
    code, text, _ = run(["on"], FakeAdb(devices="List of devices attached\nABC123\tdevice\nDEF456\tdevice\n"))
    assert code == 1 and "--serial" in text


def test_menu_choice_runs_action():
    code, _, adb = run([], reads=["1"])
    assert code == 0
    assert ["-s", "ABC123", "shell", "wm", "size", "1080x1440"] in adb.calls
    code, text, _ = run([], reads=["9"])
    assert code == 1 and "Ничего не выбрано" in text


def test_missing_adb_explains_how_to_get_it(monkeypatch):
    monkeypatch.setattr(tv, "find_adb", lambda explicit=None: None)
    out = []
    code = tv.main(["on"], runner=FakeAdb(), out=out.append)
    assert code == 1
    assert "platform-tools" in "\n".join(out)


def test_find_adb_prefers_folder_next_to_script(tmp_path, monkeypatch):
    monkeypatch.undo()  # вернуть настоящий find_adb
    fake = tmp_path / "adb.exe"
    fake.write_text("")
    assert tv.find_adb(str(fake)) == str(fake)
