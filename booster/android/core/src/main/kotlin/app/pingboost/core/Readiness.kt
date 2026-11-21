package app.pingboost.core

enum class Level { Ok, Warn, Bad }

/** Одна строка диагностики: что проверили и что с этим делать. */
data class Check(val title: String, val detail: String, val level: Level)

/**
 * Факты о телефоне, снятые приложением. Здесь только данные, без Android, чтобы правила
 * оценки можно было проверять тестами.
 */
data class DeviceFacts(
    val maxRefreshHz: Float,
    val currentRefreshHz: Float,
    /** Значение PowerManager.THERMAL_STATUS_*: 0 норма, 1 лёгкий, 2 средний, 3+ сильный нагрев. null, если телефон не сообщает. */
    val thermalStatus: Int?,
    val powerSave: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean,
    val availRamMb: Long,
    val totalRamMb: Long,
    val dndOn: Boolean,
    val gameInstalled: Boolean,
)

enum class Verdict { Ready, AlmostReady, Problems }

object Readiness {
    /** Какой потолок FPS может показать экран: игра предлагает 60/90/120/144. */
    fun fpsCap(maxRefreshHz: Float): Int = when {
        maxRefreshHz >= 143f -> 144
        maxRefreshHz >= 119f -> 120
        maxRefreshHz >= 89f -> 90
        else -> 60
    }

    fun checks(f: DeviceFacts): List<Check> = buildList {
        add(gameCheck(f))
        add(refreshCheck(f))
        thermalCheck(f)?.let(::add)
        add(powerSaveCheck(f))
        batteryCheck(f)?.let(::add)
        add(ramCheck(f))
        add(dndCheck(f))
    }

    fun verdict(checks: List<Check>): Verdict = when {
        checks.any { it.level == Level.Bad } -> Verdict.Problems
        checks.any { it.level == Level.Warn } -> Verdict.AlmostReady
        else -> Verdict.Ready
    }

    private fun gameCheck(f: DeviceFacts) =
        if (f.gameInstalled) Check("PUBG Mobile установлен", "Можно запускать.", Level.Ok)
        else Check("PUBG Mobile не найден", "Установи игру, иначе запускать нечего.", Level.Bad)

    private fun refreshCheck(f: DeviceFacts): Check {
        val cap = fpsCap(f.maxRefreshHz)
        val max = f.maxRefreshHz.toInt()
        return when {
            cap >= 120 -> Check(
                "Экран до $max Гц",
                "Хватит на $cap FPS. В PUBG выбери «Плавная» графику и максимальную частоту кадров. " +
                    "Если $cap в списке нет, игра не поддерживает твою модель, и это не исправить приложением.",
                Level.Ok,
            )
            cap == 90 -> Check(
                "Экран до $max Гц",
                "Потолок 90 FPS: 120 и 144 на этом экране невозможны. В PUBG выбери 90 FPS, если он есть.",
                Level.Ok,
            )
            else -> Check(
                "Экран 60 Гц",
                "Выше 60 FPS на этом экране не увидеть. Проверь в настройках телефона, не выключена ли «Высокая частота обновления».",
                Level.Warn,
            )
        }
    }

    private fun thermalCheck(f: DeviceFacts): Check? {
        val s = f.thermalStatus ?: return null
        return when {
            s <= 1 -> Check("Температура в норме", "Телефон не перегрет, FPS не режется.", Level.Ok)
            s == 2 -> Check(
                "Телефон нагрет",
                "Скоро система начнёт снижать частоту, и FPS будет проседать. Сними чехол, не играй на зарядке, дай остыть пару минут.",
                Level.Warn,
            )
            else -> Check(
                "Телефон сильно перегрет",
                "Система уже режет производительность. Дай остыть, иначе FPS будет низким при любых настройках.",
                Level.Bad,
            )
        }
    }

    private fun powerSaveCheck(f: DeviceFacts) =
        if (f.powerSave) Check(
            "Включено энергосбережение",
            "Оно ограничивает частоту экрана и FPS. Выключи его на время игры.",
            Level.Bad,
        ) else Check("Энергосбережение выключено", "Ограничений по FPS нет.", Level.Ok)

    private fun batteryCheck(f: DeviceFacts): Check? {
        val pct = f.batteryPercent ?: return null
        return if (pct < 20 && !f.charging) Check(
            "Заряд $pct%",
            "При низком заряде многие телефоны снижают производительность. Подключи зарядку.",
            Level.Warn,
        ) else Check("Заряд $pct%" + if (f.charging) " (заряжается)" else "", "Достаточно.", Level.Ok)
    }

    private fun ramCheck(f: DeviceFacts) = when {
        f.availRamMb < 600 -> Check(
            "Мало свободной памяти: ${f.availRamMb} МБ из ${f.totalRamMb}",
            "Закрой лишние приложения (смахни их из списка недавних), иначе игра будет подвисать.",
            Level.Bad,
        )
        f.availRamMb < 1200 -> Check(
            "Свободно ${f.availRamMb} МБ из ${f.totalRamMb}",
            "Немного. Закрой тяжёлые приложения (браузер, видео, соцсети) перед игрой.",
            Level.Warn,
        )
        else -> Check("Свободно ${f.availRamMb} МБ из ${f.totalRamMb}", "Памяти хватает.", Level.Ok)
    }

    private fun dndCheck(f: DeviceFacts) =
        if (f.dndOn) Check("«Не беспокоить» включён", "Уведомления не будут сворачивать игру.", Level.Ok)
        else Check(
            "«Не беспокоить» выключен",
            "Уведомление или звонок посреди боя может свернуть игру. Включи режим кнопкой ниже.",
            Level.Warn,
        )
}
