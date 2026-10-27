package sticker.logic

import kotlin.test.*

class StickerSearchRulesTest {
    @Test fun literalPatternDoesNotTurnUserTextIntoWildcards() {
        assertEquals("%100!%!_!!' OR 1=1 --%", StickerSearchRules.pattern(" 100%_!' OR 1=1 -- "))
        assertEquals("%smile%", StickerSearchRules.pattern(" smile "))
        assertEquals("%\\%", StickerSearchRules.pattern("\\"))
        assertNull(StickerSearchRules.pattern("   "))
    }
    @Test fun queryAndResultsAreBounded() {
        assertNotNull(StickerSearchRules.pattern("a".repeat(128)))
        assertFails { StickerSearchRules.pattern("a".repeat(129)) }
        for (text in listOf("a\u0000b", "a\nb", "a\tb")) assertFails { StickerSearchRules.pattern(text) }
        assertEquals(20, StickerSearchRules.MAX_PACKS)
        assertEquals(100, StickerSearchRules.MAX_STICKERS)
    }
}
