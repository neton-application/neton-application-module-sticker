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
    private fun frame(w: Int = 1, h: Int = 1, x: Int = 0, y: Int = 0, payload: ByteArray = lossless(w,h), duration: Long = 34) =
        chunk("ANMF", le(x.toLong(),3) + le(y.toLong(),3) + le((w-1).toLong(),3) + le((h-1).toLong(),3) + le(duration,3) + byteArrayOf(0) + payload)
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
    @Test fun enforcesAnimationTimingAndFrameLimits() {
        StickerArchive.image(animation(512,256,List(88) { frame(512,256) }))
        StickerArchive.image(animation(frames=listOf(frame(duration=3000))))
        StickerArchive.image(animation(frames=listOf(frame(duration=34),frame(duration=2966))))
        for (duration in listOf(0L,1L,33L)) {
            rejected("webp_frame_duration") { StickerArchive.image(animation(frames=listOf(frame(duration=duration)))) }
        }
        for (duration in listOf(3001L,0xffffffL)) {
            rejected("webp_animation_duration") { StickerArchive.image(animation(frames=listOf(frame(duration=duration)))) }
        }
        rejected("webp_animation_duration") { StickerArchive.image(animation(frames=listOf(frame(duration=1500),frame(duration=1501)))) }
        // At >=34ms per frame, the 3000ms ceiling is stricter than the 90-frame ceiling.
        assertEquals(90,StickerRules.MAX_ANIMATION_FRAMES)
        for (count in listOf(89,90,91,256)) {
            rejected("webp_animation_duration") { StickerArchive.image(animation(frames=List(count) { frame() })) }
        }
    }
    @Test fun rejectsMalformedAnimationHeadersAndFrames() {
        rejected("invalid_webp") { StickerArchive.image(webp(extended(),chunk("ANIM",ByteArray(5)),frame())) }
        rejected("invalid_webp") { StickerArchive.image(webp(extended(),chunk("ANIM",ByteArray(6)))) }
        rejected("invalid_webp") { StickerArchive.image(webp(extended(),chunk("ANIM",ByteArray(6)),chunk("ANMF",ByteArray(15)))) }
        rejected("invalid_webp") { StickerArchive.image(webp(extended(),chunk("ANIM",ByteArray(6)),chunk("ANIM",ByteArray(6)),frame())) }
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val body=type.encodeToByteArray()+data
        return be(data.size.toLong())+body+be(StickerArchive.crc(body))
    }
    private fun png(depth: Int = 8, color: Int = 6, compression: Int = 0, duplicate: Boolean = false, w: Int = 1, h: Int = 1): ByteArray {
        val header=pngChunk("IHDR",be(w.toLong())+be(h.toLong())+byteArrayOf(depth.toByte(),color.toByte(),compression.toByte(),0,0))
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
    private fun lossy(w: Int, h: Int) = chunk("VP8 ",byteArrayOf(0,0,0,-99,1,42)+le(w.toLong(),2)+le(h.toLong(),2))
    @Test fun acceptsRectangularGeometryAndRejects513OnEitherAxis() {
        for ((w,h) in listOf(240 to 240,320 to 180,512 to 256,256 to 512,512 to 512,1 to 512,512 to 1)) {
            for (bytes in listOf(png(w=w,h=h),webp(lossless(w,h)),webp(lossy(w,h)),webp(extended(w,h,0),lossless(w,h)),animation(w,h))) {
                val image=StickerArchive.image(bytes)
                assertEquals(w,image.width); assertEquals(h,image.height)
            }
        }
        for ((w,h) in listOf(513 to 256,256 to 513,513 to 513)) {
            for (bytes in listOf(png(w=w,h=h),webp(lossless(w,h)),webp(lossy(w,h)),webp(extended(w,h,0),lossless(w,h)),animation(w,h))) {
                rejected("invalid_dimensions") { StickerArchive.image(bytes) }
            }
        }
    }
    @Test fun enforcesExact500KiBImageLimit() {
        for (animated in listOf(false,true)) {
            val chunks=if(animated) arrayOf(extended(),chunk("ANIM",ByteArray(6)),frame()) else arrayOf(lossless())
            val padding=StickerRules.MAX_IMAGE-webp(*chunks).size-8
            assertEquals(StickerRules.MAX_IMAGE,StickerArchive.image(webp(*chunks,chunk("EXIF",ByteArray(padding)))).bytes.size)
            rejected("image_size") { StickerArchive.image(webp(*chunks,chunk("EXIF",ByteArray(padding+2)))) }
        }
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
    private fun manifest(schema: String = "", w: Int = 1, h: Int = 1, cover: String = "a.webp") =
        """{${schema}"pack_id":"safe","name":{"en":"Safe"},"author":"Admin","version":1,"cover":"$cover","stickers":[{"key":"a","file":"a.webp","width":$w,"height":$h}]}"""
    private fun normalZip(offset: Long? = null, deflated: Boolean = false, descriptor: Boolean = false,
                          manifest: String = manifest(), data: ByteArray = webp(lossless()), cover: ByteArray? = null): ByteArray {
        val first=ZipEntry("manifest.json",manifest.encodeToByteArray())
        val header=local(first)
        val compressed=if(deflated) byteArrayOf(1)+le(data.size.toLong(),2)+le(data.size.inv().toLong(),2)+data else data
        val second=ZipEntry("a.webp",data,offset ?: header.size.toLong(),if(deflated) 8 else 0,compressed.size.toLong(), flags=if(descriptor) 8 else 0)
        val secondLocal=local(second).dropLast(data.size).toByteArray()+compressed+
            (if(descriptor) le(0x08074b50)+le(StickerArchive.crc(data))+le(compressed.size.toLong())+le(data.size.toLong()) else byteArrayOf())
        val third=cover?.let { ZipEntry("cover.webp",it,(header.size+secondLocal.size).toLong()) }
        return zip(header+secondLocal+(third?.let { local(it) } ?: byteArrayOf()),listOfNotNull(first,second,third))
    }
    @Test fun acceptsLegacyAndExplicitSchemaV1WithoutChangingKeys() {
        for (schema in listOf("","\"schema_version\":1,")) {
            val bundle=StickerArchive.unpack(normalZip(manifest=manifest(schema)))
            assertEquals(1,bundle.manifest.schemaVersion)
            assertEquals("a",bundle.manifest.stickers.single().key)
        }
    }
    @Test fun rejectsUnsupportedAndMalformedSchemaVersions() {
        for (version in listOf("0","2","-1","2147483647")) {
            rejected("unsupported_schema_version") { StickerArchive.unpack(normalZip(manifest=manifest("\"schema_version\":$version,"))) }
        }
        for (version in listOf("null","true","1.5","{}","2147483648")) {
            rejected("invalid_manifest") { StickerArchive.unpack(normalZip(manifest=manifest("\"schema_version\":$version,"))) }
        }
    }
    @Test fun zipImagesAndCoversUseTheSameIngressLimits() {
        assertEquals(512,StickerArchive.unpack(normalZip(manifest=manifest(w=512,h=256),data=animation(512,256))).images.getValue("a.webp").width)
        for (bad in listOf(png(w=513),webp(lossless(1,513)),animation(frames=listOf(frame(duration=33))),
                           animation(frames=listOf(frame(duration=3001))),animation(frames=List(91) { frame() }),
                           animation(frames=listOf(frame(payload=ByteArray(8)))))) {
            rejected { StickerArchive.unpack(normalZip(data=bad)) }
            rejected { StickerArchive.unpack(normalZip(manifest=manifest(cover="cover.webp"),cover=bad)) }
        }
        rejected("dimension_mismatch") { StickerArchive.unpack(normalZip(manifest=manifest(w=2))) }
    }
    @Test fun zipStickerAndIndependentCoverEnforce500KiB() {
        val padding=StickerRules.MAX_IMAGE-webp(lossless()).size-8
        val exact=webp(lossless(),chunk("EXIF",ByteArray(padding)))
        val oversized=webp(lossless(),chunk("EXIF",ByteArray(padding+2)))
        assertEquals(2,StickerArchive.unpack(normalZip(manifest=manifest(cover="cover.webp"),data=exact,cover=exact)).images.size)
        rejected("zip_entry_size") { StickerArchive.unpack(normalZip(data=oversized)) }
        rejected("zip_entry_size") { StickerArchive.unpack(normalZip(manifest=manifest(cover="cover.webp"),cover=oversized)) }
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
