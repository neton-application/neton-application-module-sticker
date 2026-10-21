package sticker.logic

import neton.core.http.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StickerArchiveTest {
    private fun le(value: Long, count: Int = 4) = ByteArray(count) { (value ushr (it * 8)).toByte() }
    private fun be(value: Long) = le(value).reversedArray()
    private fun chunk(type: String, data: ByteArray) = type.encodeToByteArray() + le(data.size.toLong()) + data + ByteArray(data.size and 1)
    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".encodeToByteArray() + chunks.fold(byteArrayOf()) { a, b -> a + b }
        return "RIFF".encodeToByteArray() + le(body.size.toLong()) + body
    }
    // These are structural header fixtures, deliberately not full codec streams.
    private fun lossless(w: Int = 1, h: Int = 1) = chunk("VP8L", byteArrayOf(47) + le((w-1).toLong() or ((h-1).toLong() shl 14)))
    private fun extended(w: Int = 1, h: Int = 1, flags: Int = 2) = chunk("VP8X", byteArrayOf(flags.toByte(),0,0,0) + le((w-1).toLong(),3) + le((h-1).toLong(),3))
    private fun frame(w: Int = 1, h: Int = 1, x: Int = 0, y: Int = 0, payload: ByteArray = lossless(w,h)) =
        chunk("ANMF", le(x.toLong(),3) + le(y.toLong(),3) + le((w-1).toLong(),3) + le((h-1).toLong(),3) + le(10,3) + byteArrayOf(0) + payload)
    private fun animation(w: Int = 1, h: Int = 1, frames: List<ByteArray> = listOf(frame(w,h))) =
        webp(extended(w,h), chunk("ANIM", ByteArray(6)), *frames.toTypedArray())
    private fun rejected(code: String? = null, block: () -> Unit) {
        val error = assertFailsWith<BadRequestException>(block = block)
        if (code != null) assertTrue(error.message.orEmpty().lowercase().contains(code), error.message)
    }

    @Test fun acceptsStaticAndAnimatedHeaders() {
        assertEquals(1, StickerArchive.image(webp(lossless())).width)
        assertEquals(1, StickerArchive.image(webp(extended(flags=0), lossless())).height)
        assertEquals(1, StickerArchive.image(animation()).width)
    }
    @Test fun rejectsOversizedBitstreamBehindSmallCanvas() {
        rejected("invalid_dimensions") { StickerArchive.image(webp(extended(flags=0), lossless(4097,1))) }
        rejected("dimension_mismatch") { StickerArchive.image(webp(extended(flags=0), lossless(2,1))) }
    }
    @Test fun rejectsFrameOutsideCanvasAndMismatchedBitstream() {
        rejected("invalid_webp_frame") { StickerArchive.image(animation(frames=listOf(frame(x=1)))) }
        rejected("invalid_webp_frame") { StickerArchive.image(animation(frames=listOf(frame(y=1)))) }
        rejected("dimension_mismatch") { StickerArchive.image(animation(frames=listOf(frame(payload=lossless(2,1))))) }
    }
    @Test fun rejectsMissingAndTruncatedFrameData() {
        rejected { StickerArchive.image(animation(frames=listOf(frame(payload=ByteArray(8))))) }
        val overflowChunk = "VP8L".encodeToByteArray() + le(0xffffffffL)
        rejected("invalid_webp_frame") { StickerArchive.image(animation(frames=listOf(frame(payload=overflowChunk)))) }
        val missingPadding = "VP8L".encodeToByteArray() + le(5) + byteArrayOf(47,0,0,0,0)
        rejected("invalid_webp_frame") { StickerArchive.image(animation(frames=listOf(frame(payload=missingPadding)))) }
    }
    @Test fun rejectsAnimationOrderingAndDuplicateBitstreams() {
        rejected { StickerArchive.image(webp(extended(), frame())) }
        rejected { StickerArchive.image(webp(extended(), chunk("ANIM",ByteArray(6)), lossless())) }
        rejected { StickerArchive.image(webp(lossless(), lossless())) }
        rejected { StickerArchive.image(animation(frames=listOf(frame(payload=lossless()+lossless())))) }
        rejected { StickerArchive.image(webp(extended(flags=0), extended(flags=0), lossless())) }
    }
    @Test fun enforcesFrameCountAndCanvasWorkBudget() {
        StickerArchive.image(animation(frames=List(StickerArchive.MAX_FRAMES) { frame() }))
        rejected("webp_frame_budget") { StickerArchive.image(animation(frames=List(StickerArchive.MAX_FRAMES+1) { frame() })) }
        StickerArchive.image(animation(4096,4096,List(4) { frame(4096,4096) }))
        rejected("webp_frame_budget") { StickerArchive.image(animation(4096,4096,List(5) { frame(4096,4096) })) }
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val body=type.encodeToByteArray()+data
        return be(data.size.toLong())+body+be(StickerArchive.crc(body))
    }
    private fun png(depth: Int = 8, color: Int = 6, compression: Int = 0, duplicate: Boolean = false): ByteArray {
        val header=pngChunk("IHDR",be(1)+be(1)+byteArrayOf(depth.toByte(),color.toByte(),compression.toByte(),0,0))
        return byteArrayOf(-119,80,78,71,13,10,26,10)+header+(if(duplicate) header else byteArrayOf())+
            pngChunk("IDAT",byteArrayOf(0))+pngChunk("IEND",byteArrayOf())
    }
    @Test fun checksPngHeaderCombinations() {
        assertEquals(1, StickerArchive.image(png()).width)
        rejected("invalid_png") { StickerArchive.image(png(depth=1,color=6)) }
        rejected("invalid_png") { StickerArchive.image(png(color=1)) }
        rejected("invalid_png") { StickerArchive.image(png(compression=1)) }
        rejected("invalid_png") { StickerArchive.image(png(duplicate=true)) }
    }

    private data class ZipEntry(val name: String, val data: ByteArray, val offset: Long = 0, val method: Int = 0,
                                val compressed: Long = data.size.toLong(), val expanded: Long = data.size.toLong(), val flags: Int = 0)
    private fun local(e: ZipEntry): ByteArray = le(0x04034b50)+le(20,2)+le(e.flags.toLong(),2)+le(e.method.toLong(),2)+le(0)+
        le(StickerArchive.crc(e.data))+le(e.compressed)+le(e.expanded)+le(e.name.length.toLong(),2)+le(0,2)+e.name.encodeToByteArray()+e.data
    private fun central(e: ZipEntry): ByteArray = le(0x02014b50)+le(20,2)+le(20,2)+le(e.flags.toLong(),2)+le(e.method.toLong(),2)+le(0)+
        le(StickerArchive.crc(e.data))+le(e.compressed)+le(e.expanded)+le(e.name.length.toLong(),2)+le(0,2)+le(0,2)+le(0,2)+le(0,2)+le(0)+le(e.offset)+e.name.encodeToByteArray()
    private fun zip(locals: ByteArray, entries: List<ZipEntry>): ByteArray {
        val directory=entries.fold(byteArrayOf()) { a,e -> a+central(e) }
        return locals+directory+le(0x06054b50)+le(0,2)+le(0,2)+le(entries.size.toLong(),2)+le(entries.size.toLong(),2)+
            le(directory.size.toLong())+le(locals.size.toLong())+le(0,2)
    }
    private fun normalZip(offset: Long? = null, deflated: Boolean = false, descriptor: Boolean = false): ByteArray {
        val manifest="""{"pack_id":"safe","name":{"en":"Safe"},"author":"Admin","version":1,"cover":"a.webp","stickers":[{"key":"a","file":"a.webp","width":1,"height":1}]}""".encodeToByteArray()
        val first=ZipEntry("manifest.json",manifest)
        val header=local(first)
        val data=webp(lossless())
        val compressed=if(deflated) byteArrayOf(1)+le(data.size.toLong(),2)+le(data.size.inv().toLong(),2)+data else data
        val second=ZipEntry("a.webp",data,offset ?: header.size.toLong(),if(deflated) 8 else 0,compressed.size.toLong(), flags=if(descriptor) 8 else 0)
        val secondLocal=local(second).dropLast(data.size).toByteArray()+compressed+
            (if(descriptor) le(0x08074b50)+le(StickerArchive.crc(data))+le(compressed.size.toLong())+le(data.size.toLong()) else byteArrayOf())
        return zip(header+secondLocal,listOf(first,second))
    }
    @Test fun acceptsStoredDeflatedAndDescriptorZips() {
        assertEquals("safe",StickerArchive.unpack(normalZip()).manifest.packId)
        assertEquals(1,StickerArchive.unpack(normalZip(deflated=true)).images.size)
        assertEquals(1,StickerArchive.unpack(normalZip(deflated=true,descriptor=true)).images.size)
    }
    @Test fun rejectsUnsignedAndOverflowingOffsetsAsBadRequest() {
        for (offset in listOf(0x7fffffffL,0xfffffff0L,0xffffffffL)) {
            rejected("invalid_zip") { StickerArchive.unpack(normalZip(offset)) }
        }
    }
    @Test fun rejectsOverlappingLocalRecordsBeforePayloadParsing() {
        val inner=ZipEntry("b.webp",byteArrayOf(1),offset=31)
        val outer=ZipEntry("a",local(inner))
        rejected("zip_overlap") { StickerArchive.unpack(zip(local(outer),listOf(outer,inner))) }
    }
    @Test fun rejectsCompressedWorkAmplificationBeforeInflating() {
        val size=17*1024*1024
        val inner=ZipEntry("b",byteArrayOf(0),offset=31,method=8,compressed=(size-31).toLong(),expanded=1)
        val payload=ByteArray(size)
        local(inner).copyInto(payload)
        val outer=ZipEntry("a",payload,method=8,expanded=1)
        rejected("compressed_size") { StickerArchive.unpack(zip(local(outer),listOf(outer,inner))) }
    }
    @Test fun rejectsStoredSizeMismatchBeforeCopying() {
        val first=ZipEntry("a",byteArrayOf(1,2),expanded=1)
        rejected("zip_entry_size") { StickerArchive.unpack(zip(local(first),listOf(first,first.copy(name="b")))) }
    }
    @Test fun rejectsTruncatedCentralDirectory() {
        val bytes=normalZip()
        val end=bytes.size-22
        le(2).copyInto(bytes,end+12)
        le((end-2).toLong()).copyInto(bytes,end+16)
        rejected("invalid_zip") { StickerArchive.unpack(bytes) }
    }
    @Test fun inflateRejectsOversizedOutputTruncationAndTrailingData() {
        val raw=byteArrayOf(1,2,0,-3,-1,65,66)
        assertEquals(listOf<Byte>(65,66),inflateRaw(raw,2).toList())
        rejected("zip_inflate") { inflateRaw(raw,1) }
        rejected("zip_inflate") { inflateRaw(raw.copyOf(raw.size-1),2) }
        rejected("zip_inflate") { inflateRaw(raw+byteArrayOf(0),2) }
    }
}
