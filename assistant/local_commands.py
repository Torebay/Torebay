"""Useful PC commands and bounded arithmetic without a cloud model."""
import ast
import math
import operator
import re


def calculate(expression):
    if len(expression) > 200:
        raise ValueError("Слишком длинная формула")
    expression = expression.replace("^", "**").replace(",", ".")
    tree = ast.parse(expression, mode="eval")
    operators = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul,
                 ast.Div: operator.truediv, ast.Mod: operator.mod, ast.Pow: operator.pow}
    if sum(1 for _ in ast.walk(tree)) > 70:
        raise ValueError("Слишком сложная формула")

    def evaluate(node):
        if isinstance(node, ast.Constant) and type(node.value) in (int, float):
            result = node.value
        elif isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            result = evaluate(node.operand) * (-1 if isinstance(node.op, ast.USub) else 1)
        elif isinstance(node, ast.BinOp) and type(node.op) in operators:
            a, b = evaluate(node.left), evaluate(node.right)
            if isinstance(node.op, ast.Pow) and abs(b) > 100:
                raise ValueError("Слишком большая степень")
            result = operators[type(node.op)](a, b)
        else:
            raise ValueError("Используйте числа, скобки и знаки + - * / ^ %")
        if not isinstance(result, (int, float)) or not math.isfinite(result) or abs(result) > 1e100:
            raise ValueError("Число слишком большое")
        return result
    return evaluate(tree.body)


def handle_local(text, tools):
    phrase = text.lower().strip().rstrip(".!?")
    from .weather import weather_answer
    weather = weather_answer(phrase)
    if weather is not None:
        return weather
    from .markets import market_answer
    market = market_answer(phrase)
    if market is not None:
        return market
    if phrase in ("проверь голос", "проверка голоса"):
        return "Привет! Я Картал. Проверяем чёткость речи: один, два, три. Готов к работе."
    for prefix in ("посчитай ", "вычисли ", "считай "):
        if phrase.startswith(prefix):
            try:
                result = calculate(phrase[len(prefix):])
                return f"Результат: {result:g}"
            except (ValueError, SyntaxError, ZeroDivisionError, OverflowError):
                return "Не удалось вычислить. Пример: посчитай (120 + 30) * 2. Деление на ноль недопустимо."
    if tools is None:
        return None
    volume = {"сделай громче": "up", "громче": "up", "сделай тише": "down", "тише": "down",
              "выключи звук": "mute", "включи звук": "mute", "без звука": "mute"}
    if phrase in volume:
        result = tools.execute("volume", {"action": volume[phrase]})
        return "Громкость изменена." if result.startswith(("Done", "Volume")) else result
    folders = {"загрузки": "downloads", "документы": "documents", "рабочий стол": "desktop",
               "изображения": "pictures", "музыку": "music", "видео": "videos"}
    for name, folder in folders.items():
        if phrase in (f"открой {name}", f"открой папку {name}"):
            result = tools.execute("open_folder", {"folder": folder})
            return f"Открываю {name}." if result.startswith("Opened") else result
    if phrase.startswith("найди файл "):
        return tools.execute("find_files", {"name": text[len("найди файл "):].strip()})
    return None
