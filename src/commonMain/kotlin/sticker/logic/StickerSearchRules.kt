package sticker.logic

internal object StickerSearchRules {
    const val MAX_FAVORITES = 1000
    const val MAX_PACKS = 20
    const val MAX_STICKERS = 100

    fun pattern(query: String): String? {
        StickerRules.check(query.length <= 128 && query.none { it < ' ' }, "invalid_search_query")
        val text = query.trim()
        if (text.isEmpty()) return null
        // Use an explicit non-backslash SQL escape character, independent of server string settings.
        return "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
    }
}
