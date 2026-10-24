package sticker.logic

import kotlin.test.*
import neton.core.http.BadRequestException

class ExternalPagInspectorTest {
    @Test fun fixedAbsoluteExecutableRequired() {
        assertFailsWith<IllegalArgumentException> { configuredPagInspector("pag-inspect") }
        assertFailsWith<IllegalArgumentException> { configuredPagInspector("/bin/true\u0000ignored") }
    }
    @Test fun unavailableNonzeroAndEmptyOutputNeverValidate() {
        for (path in listOf("/definitely-missing-privchat-pag-inspector","/usr/bin/false","/usr/bin/true")) {
            assertFailsWith<BadRequestException> { configuredPagInspector(path).inspect(byteArrayOf(80,65,71)) }
        }
    }
}
