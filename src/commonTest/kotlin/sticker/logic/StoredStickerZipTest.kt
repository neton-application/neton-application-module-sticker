package sticker.logic

import kotlin.test.*

class StoredStickerZipTest {
    @Test fun deterministicContiguousStoredRecords() {
        val files = listOf("manifest.json" to "{}".encodeToByteArray(), "r_123.png" to byteArrayOf(1, 2, 3))
        val bytes = StoredStickerZip.write(files)
        assertContentEquals(bytes, StoredStickerZip.write(files))
        fun u(p: Int, n: Int) = (0 until n).sumOf { (bytes[p + it].toLong() and 255) shl (8 * it) }
        var p = 0
        val offsets = mutableListOf<Int>()
        for ((name, data) in files) {
            offsets += p
            assertEquals(0x04034b50L, u(p, 4)); assertEquals(20L, u(p + 4, 2)); assertEquals(0x800L, u(p + 6, 2))
            assertEquals(0L, u(p + 8, 2)); assertEquals(0L, u(p + 10, 2)); assertEquals(0x21L, u(p + 12, 2))
            assertEquals(StickerArchive.crc(data), u(p + 14, 4)); assertEquals(data.size.toLong(), u(p + 18, 4)); assertEquals(data.size.toLong(), u(p + 22, 4))
            assertEquals(0L, u(p + 28, 2)); assertEquals(name, bytes.copyOfRange(p + 30, p + 30 + name.length).decodeToString())
            p += 30 + name.length
            assertContentEquals(data, bytes.copyOfRange(p, p + data.size)); p += data.size
        }
        val central = p
        for ((index, file) in files.withIndex()) {
            assertEquals(0x02014b50L, u(p, 4)); assertEquals(20L, u(p + 4, 2)); assertEquals(20L, u(p + 6, 2))
            assertEquals(0x800L, u(p + 8, 2)); assertEquals(0L, u(p + 10, 2)); assertEquals(0x21L, u(p + 14, 2))
            assertEquals(StickerArchive.crc(file.second), u(p + 16, 4)); assertEquals(0L, u(p + 30, 2)); assertEquals(0L, u(p + 32, 2))
            assertEquals(0L, u(p + 34, 2)); assertEquals(0L, u(p + 36, 2)); assertEquals(0L, u(p + 38, 4))
            assertEquals(offsets[index].toLong(), u(p + 42, 4)); p += 46 + file.first.length
        }
        assertEquals(0x06054b50L, u(p, 4)); assertEquals(files.size.toLong(), u(p + 8, 2))
        assertEquals((p - central).toLong(), u(p + 12, 4)); assertEquals(central.toLong(), u(p + 16, 4)); assertEquals(bytes.size, p + 22)
    }

    @Test fun rejectsInvalidNamesDuplicatesAndBudgets() {
        val manifest = "manifest.json" to byteArrayOf(1)
        assertFails { StoredStickerZip.write(listOf(manifest, "../x.png" to byteArrayOf())) }
        assertFails { StoredStickerZip.write(listOf(manifest, manifest)) }
        assertFails { StoredStickerZip.write(listOf("manifest.json" to ByteArray(StoredStickerZip.MAX_MANIFEST + 1))) }
        assertFails { StoredStickerZip.write(listOf(manifest) + List(514) { "r_${it + 1}.png" to byteArrayOf() }) }
    }

    @Test fun phaseOneBudgetIncludesEveryZipHeaderBeforeAllocation() {
        assertEquals(33554431, StoredStickerZip.MAX_BYTES)
        val overhead = StoredStickerZip.encodedSize(listOf("manifest.json" to 2L, "r_1.png" to 0L))
        val payload = (StoredStickerZip.MAX_BYTES - overhead).toLong()
        assertEquals(33554431, StoredStickerZip.encodedSize(listOf("manifest.json" to 2L, "r_1.png" to payload)))
        assertFails { StoredStickerZip.encodedSize(listOf("manifest.json" to 2L, "r_1.png" to payload + 1)) }
        assertFails { StoredStickerZip.encodedSize(listOf("manifest.json" to 2L, "r_1.png" to Long.MAX_VALUE)) }
    }
}
