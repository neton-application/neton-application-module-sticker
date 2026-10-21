import kotlin.test.*
import sticker.logic.StickerRules
import sticker.dto.*
import neton.core.http.BadRequestException

class StickerRulesTest {
    @Test fun publicationTransitionsAreClosed() {
        StickerRules.transition(1,2); StickerRules.transition(2,3)
        for(from in 0..4) for(to in 0..4) if((from to to) !in listOf(1 to 2,2 to 3)) assertFailsWith<BadRequestException> { StickerRules.transition(from,to) }
        assertFailsWith<BadRequestException> { StickerRules.draft(2) }
        assertFailsWith<BadRequestException> { StickerRules.draft(3) }
    }
    @Test fun identitiesAndNamesAreBounded() {
        for(id in listOf("0","-1","abc","9223372036854775808")) assertFailsWith<BadRequestException> { StickerRules.id(id) }
        StickerRules.pack(PackWrite("classic",mapOf("zh-CN" to "经典"),"1",1))
        assertFailsWith<BadRequestException> { StickerRules.pack(PackWrite("../evil",mapOf("en" to "name"),"1",1)) }
        assertFailsWith<BadRequestException> { StickerRules.pack(PackWrite("valid",emptyMap(),"1",1)) }
    }
}
