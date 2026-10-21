package sticker.logic

import kotlinx.serialization.json.Json
import sticker.dto.Manifest

internal expect fun inflateRaw(input: ByteArray, expected: Int): ByteArray

object StickerArchive {
    // Engineering safety ceilings, not product quotas. Bound compressed work and
    // animation canvas compositing even when files are tiny. No full image decode.
    internal const val MAX_COMPRESSED = 32 * 1024 * 1024 - 1
    internal const val MAX_FRAMES = 256
    internal const val MAX_FRAME_PIXELS = 64L * 1024 * 1024
    data class Image(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)
    data class Bundle(val manifest: Manifest, val images: Map<String, Image>)
    private fun ByteArray.u(i: Int) = this[i].toInt() and 255
    private fun ByteArray.le(i: Int, n: Int): Long {
        StickerRules.check(n in 0..8 && i >= 0 && i <= size - n, "truncated_file")
        return (0 until n).fold(0L) { a, j -> a or (u(i+j).toLong() shl (8*j)) }
    }
    private fun ByteArray.be(i: Int): Int {
        StickerRules.check(i >= 0 && i <= size - 4, "truncated_file")
        return (0..3).fold(0) { a,j -> (a shl 8) or u(i+j) }
    }
    private fun ByteArray.text(i: Int, n: Int): String {
        StickerRules.check(n >= 0 && i >= 0 && i <= size - n, "truncated_file")
        return copyOfRange(i, i+n).decodeToString()
    }
    internal fun crc(bytes: ByteArray): Long {
        var c = -1
        for (b in bytes) { c = c xor (b.toInt() and 255); repeat(8) { c = (c ushr 1) xor (if (c and 1 != 0) 0xedb88320.toInt() else 0) } }
        return c.inv().toLong() and 0xffffffffL
    }
    fun image(b: ByteArray): Image {
        StickerRules.check(b.size in 20..StickerRules.MAX_IMAGE, "image_size")
        var w = 0; var h = 0
        val mime: String
        if (b.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) {
            mime = "image/png"
            var p = 8; var ended = false; var data = false
            while (p + 12 <= b.size) {
                val n = b.be(p)
                StickerRules.check(n >= 0 && n.toLong()+p+12 <= b.size, "invalid_png")
                val type = b.text(p+4,4)
                StickerRules.check(type != "acTL", "apng_unsupported")
                StickerRules.check(crc(b.copyOfRange(p+4,p+8+n)) == (b.be(p+8+n).toLong() and 0xffffffffL), "png_crc")
                if (p == 8) {
                    StickerRules.check(type == "IHDR" && n == 13, "invalid_png")
                    w = b.be(p+8); h = b.be(p+12)
                    val depth = b.u(p+16); val color = b.u(p+17)
                    val depths = when (color) {
                        0 -> listOf(1, 2, 4, 8, 16)
                        2, 4, 6 -> listOf(8, 16)
                        3 -> listOf(1, 2, 4, 8)
                        else -> emptyList()
                    }
                    StickerRules.check(depth in depths && b.u(p+18) == 0 && b.u(p+19) == 0 && b.u(p+20) in 0..1, "invalid_png")
                }
                else StickerRules.check(type != "IHDR", "invalid_png")
                if (type == "IDAT") data = true
                p += n+12
                if (type == "IEND") { StickerRules.check(n == 0 && p == b.size, "invalid_png"); ended = true; break }
            }
            StickerRules.check(ended && data, "invalid_png")
        } else {
            StickerRules.check(b.text(0,4) == "RIFF" && b.text(8,4) == "WEBP" && b.le(4,4)+8 == b.size.toLong(), "unsupported_image")
            mime = "image/webp"
            var p = 12; var imageData = false
            var extended = false; var animated = false; var animationHeader = false
            var frames = 0; var pixels = 0L; var alpha = false
            while (p+8 <= b.size) {
                val type = b.text(p,4); val n = b.le(p+4,4)
                StickerRules.check(n <= b.size-p-8, "invalid_webp")
                val q = p+8
                when(type) {
                    "VP8X" -> {
                        StickerRules.check(p == 12 && !extended && n == 10L && b.u(q) and 0xc1 == 0 && b.le(q+1,3) == 0L, "invalid_webp")
                        extended = true; animated = b.u(q) and 2 != 0
                        w = b.le(q+4,3).toInt()+1; h = b.le(q+7,3).toInt()+1
                        StickerRules.check(w in 1..4096 && h in 1..4096, "invalid_dimensions")
                    }
                    "ANIM" -> {
                        StickerRules.check(animated && !animationHeader && frames == 0 && n == 6L, "invalid_webp")
                        animationHeader = true
                    }
                    "ALPH" -> {
                        StickerRules.check(extended && !animated && !imageData && !alpha && n >= 1, "invalid_webp")
                        alpha = true
                    }
                    "VP8 ", "VP8L" -> {
                        StickerRules.check(!animated && !imageData && !(alpha && type == "VP8L"), "invalid_webp")
                        val dimensions = webpDimensions(b, type, q, n.toInt())
                        if (extended) StickerRules.check(dimensions == (w to h), "dimension_mismatch")
                        else { w = dimensions.first; h = dimensions.second }
                        imageData = true
                    }
                    "ANMF" -> {
                        StickerRules.check(animated && animationHeader && n >= 24, "invalid_webp")
                        val x = b.le(q,3) * 2; val y = b.le(q+3,3) * 2
                        val fw = b.le(q+6,3).toInt()+1; val fh = b.le(q+9,3).toInt()+1
                        StickerRules.check(x + fw <= w && y + fh <= h && b.u(q+15) and 0xfc == 0, "invalid_webp_frame")
                        frames++; pixels += w.toLong() * h
                        StickerRules.check(frames <= MAX_FRAMES && pixels <= MAX_FRAME_PIXELS, "webp_frame_budget")
                        webpFrame(b, q+16, q+n.toInt(), fw, fh)
                        imageData = true
                    }
                }
                p += 8+n.toInt()+(n.toInt() and 1)
            }
            StickerRules.check(p == b.size && imageData && (!animated || frames > 0), "invalid_webp")
        }
        StickerRules.check(w in 1..4096 && h in 1..4096, "invalid_dimensions")
        return Image(b,mime,w,h)
    }
    private fun webpDimensions(b: ByteArray, type: String, q: Int, n: Int): Pair<Int, Int> {
        val dimensions = if (type == "VP8 ") {
            StickerRules.check(n >= 10 && b.u(q) and 1 == 0 && b.u(q+3)==157 && b.u(q+4)==1 && b.u(q+5)==42, "invalid_webp")
            (b.le(q+6,2).toInt() and 16383) to (b.le(q+8,2).toInt() and 16383)
        } else {
            StickerRules.check(n >= 5 && b.u(q)==47, "invalid_webp")
            val bits = b.le(q+1,4)
            StickerRules.check(bits ushr 29 == 0L, "invalid_webp")
            ((bits and 16383).toInt()+1) to (((bits ushr 14) and 16383).toInt()+1)
        }
        StickerRules.check(dimensions.first in 1..4096 && dimensions.second in 1..4096, "invalid_dimensions")
        return dimensions
    }
    private fun webpFrame(b: ByteArray, start: Int, end: Int, w: Int, h: Int) {
        var p = start; var image = false; var alpha = false
        while (p <= end - 8) {
            val type = b.text(p,4); val n = b.le(p+4,4)
            StickerRules.check(n <= end-p-8, "invalid_webp_frame")
            when (type) {
                "ALPH" -> { StickerRules.check(!alpha && !image && n >= 1, "invalid_webp_frame"); alpha = true }
                "VP8 ", "VP8L" -> {
                    StickerRules.check(!image && !(alpha && type == "VP8L"), "invalid_webp_frame")
                    StickerRules.check(webpDimensions(b,type,p+8,n.toInt()) == (w to h), "dimension_mismatch")
                    image = true
                }
                else -> StickerRules.check(false, "invalid_webp_frame")
            }
            p += 8+n.toInt()+(n.toInt() and 1)
        }
        StickerRules.check(p == end && image, "invalid_webp_frame")
    }
    fun unpack(bytes: ByteArray): Bundle {
        StickerRules.check(bytes.size in 22..StickerRules.MAX_PACKAGE, "archive_size")
        val end = (bytes.size-22 downTo maxOf(0,bytes.size-65557)).firstOrNull { bytes.le(it,4)==0x06054b50L }
            ?: throw neton.core.http.BadRequestException("STICKER_INVALID_ZIP")
        StickerRules.check(bytes.le(end+4,2)==0L && bytes.le(end+6,2)==0L && end+22+bytes.le(end+20,2)==bytes.size.toLong(), "invalid_zip")
        val count=bytes.le(end+10,2).toInt()
        StickerRules.check(count in 2..258 && bytes.le(end+8,2)==count.toLong(), "zip_entries")
        val centralOffset=bytes.le(end+16,4)
        StickerRules.check(centralOffset+bytes.le(end+12,4)==end.toLong(), "invalid_zip")
        var p=centralOffset.toInt()
        val central=p; var total=0L; var compressedTotal=0L
        data class Entry(val name: String, val start: Int, val compressed: Int, val size: Int, val method: Int, val crc: Long)
        val entries=mutableListOf<Entry>()
        val ranges=mutableListOf<LongRange>()
        val namesSeen=mutableSetOf<String>()
        // Validate ALL entries and work budgets before allocating or inflating any payload.
        repeat(count) {
            StickerRules.check(p <= end-46, "invalid_zip")
            StickerRules.check(bytes.le(p,4)==0x02014b50L, "invalid_zip")
            val flags=bytes.le(p+8,2).toInt(); val method=bytes.le(p+10,2).toInt()
            val crc=bytes.le(p+16,4); val compressed=bytes.le(p+20,4); val size=bytes.le(p+24,4)
            val names=bytes.le(p+28,2).toInt(); val extra=bytes.le(p+30,2).toInt(); val comment=bytes.le(p+32,2).toInt()
            StickerRules.check(flags and 0xF7F6 == 0 && method in listOf(0,8) && bytes.le(p+34,2)==0L, "unsupported_zip")
            StickerRules.check(names in 1..128 && p+46+names+extra+comment <= end, "invalid_zip")
            val name=bytes.text(p+46,names); StickerRules.filename(name)
            StickerRules.check(namesSeen.add(name) && size in 1..(if(name=="manifest.json") 128*1024 else StickerRules.MAX_IMAGE), "zip_entry_size")
            total+=size; StickerRules.check(total <= StickerRules.MAX_PACKAGE, "expanded_size")
            compressedTotal+=compressed
            StickerRules.check(compressed > 0 && compressedTotal <= MAX_COMPRESSED, "compressed_size")
            StickerRules.check(method != 0 || compressed == size, "zip_entry_size")
            val localOffset=bytes.le(p+42,4)
            StickerRules.check(localOffset <= central.toLong()-30, "invalid_zip")
            val offset=localOffset.toInt()
            StickerRules.check(bytes.le(offset,4)==0x04034b50L && bytes.le(offset+8,2)==method.toLong() && bytes.le(offset+6,2)==flags.toLong(), "invalid_zip")
            val ln=bytes.le(offset+26,2).toInt(); val lx=bytes.le(offset+28,2).toInt()
            val start=offset+30+ln+lx
            StickerRules.check(ln==names && start <= central && bytes.text(offset+30,ln)==name && compressed <= central-start, "invalid_zip")
            var finish=start+compressed.toInt()
            if (flags and 8 == 0) {
                StickerRules.check(bytes.le(offset+14,4)==crc && bytes.le(offset+18,4)==compressed && bytes.le(offset+22,4)==size, "invalid_zip")
            } else {
                StickerRules.check(finish <= central-12, "invalid_zip")
                val descriptor=if(bytes.le(finish,4)==0x08074b50L) finish+4 else finish
                StickerRules.check(descriptor <= central-12 && bytes.le(descriptor,4)==crc && bytes.le(descriptor+4,4)==compressed && bytes.le(descriptor+8,4)==size, "invalid_zip")
                finish=descriptor+12
            }
            val range=localOffset until finish.toLong()
            StickerRules.check(ranges.none { range.first <= it.last && it.first <= range.last }, "zip_overlap")
            ranges.add(range)
            entries.add(Entry(name,start,compressed.toInt(),size.toInt(),method,crc))
            p+=46+names+extra+comment
        }
        StickerRules.check(p==end, "invalid_zip")
        val files=linkedMapOf<String,ByteArray>()
        for (entry in entries) {
            val input=bytes.copyOfRange(entry.start,entry.start+entry.compressed)
            val output=if(entry.method==0) input else inflateRaw(input,entry.size)
            StickerRules.check(output.size==entry.size && crc(output)==entry.crc, "zip_crc")
            files[entry.name]=output
        }
        val manifest=try { Json.decodeFromString<Manifest>(files["manifest.json"]?.decodeToString() ?: "") } catch (_: kotlinx.serialization.SerializationException) { throw neton.core.http.BadRequestException("STICKER_INVALID_MANIFEST") }
        StickerRules.check(manifest.stickers.size in 1..StickerRules.MAX_STICKERS, "sticker_count")
        StickerRules.filename(manifest.cover)
        manifest.stickers.forEach { StickerRules.filename(it.file); StickerRules.check(Regex("[A-Za-z0-9_-]{1,128}").matches(it.key) && it.emoji.length <= 64,"invalid_key") }
        StickerRules.check(manifest.stickers.map { it.key }.toSet().size==manifest.stickers.size, "duplicate_key")
        val expected=manifest.stickers.map { it.file }.toSet()+manifest.cover+"manifest.json"
        StickerRules.check(files.keys==expected, "manifest_files")
        val images=files.filterKeys { it!="manifest.json" }.mapValues { image(it.value) }
        manifest.stickers.forEach { val img=images.getValue(it.file); StickerRules.check(img.width==it.width && img.height==it.height, "dimension_mismatch") }
        return Bundle(manifest,images)
    }
}
