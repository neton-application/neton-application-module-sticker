package sticker.logic

/** Delivery writer only: import validation remains in StickerArchive. */
internal object StoredStickerZip {
    const val MAX_BYTES = 32 * 1024 * 1024 - 1
    const val MAX_MANIFEST = 256 * 1024
    const val MAX_ENTRIES = 514

    fun encodedSize(files: List<Pair<String, Long>>): Int {
        StickerRules.check(files.isNotEmpty() && files.size <= MAX_ENTRIES && files.first().first == "manifest.json", "archive_entries")
        StickerRules.check(files.first().second <= MAX_MANIFEST && files.map { it.first }.toSet().size == files.size, "archive_manifest")
        StickerRules.check(files.all { it.second in 0..MAX_BYTES.toLong() }, "archive_size")
        val total = files.sumOf { (name, size) ->
            StickerRules.check(name == "manifest.json" || Regex("r_[1-9][0-9]*\\.(png|webp|pag)").matches(name), "archive_filename")
            76L + 2 * name.encodeToByteArray().size + size
        } + 22L
        StickerRules.check(total <= MAX_BYTES && files.sumOf { it.second } <= MAX_BYTES, "archive_size")
        return total.toInt()
    }

    fun write(files: List<Pair<String, ByteArray>>): ByteArray {
        val total = encodedSize(files.map { it.first to it.second.size.toLong() })
        val names = files.map { it.first.encodeToByteArray() }
        val out = ByteArray(total)
        var p = 0
        fun word(value: Long, count: Int) { repeat(count) { out[p++] = (value ushr (8 * it)).toByte() } }
        fun bytes(value: ByteArray) { value.copyInto(out, p); p += value.size }
        val offsets = IntArray(files.size)
        val checksums = files.map { StickerArchive.crc(it.second) }
        for (i in files.indices) {
            offsets[i] = p
            val data = files[i].second
            word(0x04034b50,4); word(20,2); word(0x800,2); word(0,2); word(0,2); word(0x21,2)
            word(checksums[i],4); word(data.size.toLong(),4); word(data.size.toLong(),4)
            word(names[i].size.toLong(),2); word(0,2); bytes(names[i]); bytes(data)
        }
        val central = p
        for (i in files.indices) {
            val size = files[i].second.size.toLong()
            word(0x02014b50,4); word(20,2); word(20,2); word(0x800,2); word(0,2); word(0,2); word(0x21,2)
            word(checksums[i],4); word(size,4); word(size,4); word(names[i].size.toLong(),2)
            repeat(4) { word(0,2) }; word(0,4); word(offsets[i].toLong(),4); bytes(names[i])
        }
        val centralSize = p - central
        word(0x06054b50,4); word(0,2); word(0,2); word(files.size.toLong(),2); word(files.size.toLong(),2)
        word(centralSize.toLong(),4); word(central.toLong(),4); word(0,2)
        check(p == out.size)
        return out
    }
}
