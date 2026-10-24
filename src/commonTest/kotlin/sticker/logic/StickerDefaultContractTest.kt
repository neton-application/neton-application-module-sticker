package sticker.logic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import sticker.dto.*
import kotlin.test.*

class StickerDefaultContractTest {
    @Test fun legacyWritesDefaultToOptInAndNewFlagsAreBounded() {
        val legacy = Json.decodeFromString<PackWrite>("""{"packId":"legacy","name":{"en":"Legacy"},"authorId":"1","version":1}""")
        assertEquals(0, legacy.defaultInstall)
        StickerRules.pack(legacy.copy(defaultInstall = 1))
        for (flag in listOf(-1, 2, Int.MAX_VALUE)) assertFails { StickerRules.pack(legacy.copy(defaultInstall = flag)) }
        assertFails { Json.decodeFromString<PackWrite>("""{"packId":"x","name":{"en":"X"},"authorId":"1","version":1,"defaultInstall":true}""") }
    }

    @Test fun packWireHasExplicitCoverAndDefaultFlagWithoutRenamingIdentities() {
        val pack = PackDto("1", "stable-key", mapOf("en" to "Pack"), "2", 1, 2, emptyList(), "3", 1,
            "https://example.test/app/sticker/resource/get/3")
        val encoded = Json.encodeToString(pack)
        assertTrue(encoded.contains("\"coverImageUrl\":"))
        assertTrue(encoded.contains("\"defaultInstall\":0"))
        assertEquals(pack, Json.decodeFromString<PackDto>(encoded))
    }

    @Test fun bothManifestVersionsAcceptOptionalSnakeCaseFlagOnly() {
        val v1 = """{"pack_id":"x","name":{"en":"X"},"author":"A","version":1,"cover":"a.png","stickers":[]}"""
        assertEquals(0, Json.decodeFromString<Manifest>(v1).defaultInstall)
        assertEquals(1, Json.decodeFromString<Manifest>(v1.dropLast(1) + ",\"default_install\":1}").defaultInstall)
        val v2 = v1.dropLast(1) + ",\"schema_version\":2,\"default_install\":1}"
        assertEquals(1, Json.decodeFromString<ManifestV2>(v2).defaultInstall)
        assertFails { Json.decodeFromString<Manifest>(v1.dropLast(1) + ",\"defaultInstall\":1}") }
    }
}
