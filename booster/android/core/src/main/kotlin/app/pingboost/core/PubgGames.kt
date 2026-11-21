package app.pingboost.core

/** Версия игры, которую можно пустить через туннель. */
data class GameVariant(val label: String, val packageName: String)

object PubgGames {
    val all: List<GameVariant> = listOf(
        GameVariant("PUBG Mobile (Global)", "com.tencent.ig"),
        GameVariant("PUBG Mobile Lite", "com.tencent.iglite"),
        GameVariant("PUBG Mobile (Корея)", "com.pubg.krmobile"),
        GameVariant("PUBG Mobile (Вьетнам)", "com.vng.pubgmobile"),
        GameVariant("PUBG Mobile (Тайвань)", "com.rekoo.pubgm"),
        GameVariant("Battlegrounds Mobile India", "com.pubg.imobile"),
        GameVariant("PUBG: NEW STATE", "com.pubg.newstate"),
    )

    val packages: List<String> get() = all.map { it.packageName }
}
