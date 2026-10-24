package sticker.logic

import kotlin.io.encoding.Base64
import kotlin.test.*
import neton.core.http.BadRequestException

/** Policy fixtures test the port contract, NOT evidence of official PAG decoding. */
class PagImportTest {
    private val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    private val fixture = byteArrayOf(80,65,71,0)
    private fun metadata() = PagMetadata(1,"policy-test-not-libpag",true,true,1,1,512,256,3000,30.0,false,0,10,2,0,0,0,10.0)
    private fun reject(block: () -> Unit) = assertFailsWith<BadRequestException>(block=block)
    private fun le(n: Long, size: Int = 4) = ByteArray(size) { (n ushr (8*it)).toByte() }
    private fun zip(files: Map<String,ByteArray>): ByteArray {
        var body=byteArrayOf(); var directory=byteArrayOf()
        files.forEach { (name,data) ->
            val file=name.encodeToByteArray(); val size=data.size.toLong(); val crc=StickerArchive.crc(data)
            directory += le(0x02014b50)+le(20,2)+le(20,2)+le(0,2)+le(0,2)+le(0)+le(crc)+le(size)+le(size)+le(file.size.toLong(),2)+le(0,2)+le(0,2)+le(0,2)+le(0,2)+le(0)+le(body.size.toLong())+file
            body += le(0x04034b50)+le(20,2)+le(0,2)+le(0,2)+le(0)+le(crc)+le(size)+le(size)+le(file.size.toLong(),2)+le(0,2)+file+data
        }
        return body+directory+le(0x06054b50)+le(0,2)+le(0,2)+le(files.size.toLong(),2)+le(files.size.toLong(),2)+le(directory.size.toLong())+le(body.size.toLong())+le(0,2)
    }
    private fun pack(version: Int = 2, profile: String = PagValidation.STICKER, extra: String = "", pag: ByteArray = fixture) = zip(linkedMapOf(
        "manifest.json" to """{"schema_version":$version,"pack_id":"pag","name":{"en":"PAG"},"author":"Test","version":1,"cover":"fallback.png","stickers":[{"key":"original","file":"fallback.png","width":1,"height":1,"pag":{"file":"motion.pag","profile":"$profile"}$extra}]}""".encodeToByteArray(),
        "fallback.png" to png,"motion.pag" to pag))

    @Test fun v1RemainsStrictAndV2PreservesFallbackIdentity() {
        reject { StickerArchive.unpack(pack()) }
        val bundle=StickerArchive.unpackV2(pack(),PagInspector { metadata() })
        assertEquals("original",bundle.manifest.stickers.single().key)
        assertEquals("motion.pag",bundle.pagLinks["fallback.png"])
        assertContentEquals(png,bundle.images.getValue("fallback.png").bytes)
        assertNull(bundle.images.getValue("fallback.png").pag)
        assertEquals(3000,bundle.images.getValue("motion.pag").pag?.durationMs)
    }
    @Test fun missingOfficialInspectorFailsClosed() {
        reject { StickerArchive.unpackV2(pack()) }
        reject { PagValidation.inspect(fixture,PagValidation.STICKER,configuredPagInspector(null)) }
    }
    @Test fun v2RejectsWrongSchemaUnknownFieldsAndProfile() {
        val inspector=PagInspector { metadata() }
        reject { StickerArchive.unpackV2(pack(version=1),inspector) }
        reject { StickerArchive.unpackV2(pack(version=3),inspector) }
        reject { StickerArchive.unpackV2(pack(profile=PagValidation.EFFECT),inspector) }
        reject { StickerArchive.unpackV2(pack(extra=",\"unknown\":true"),inspector) }
    }
    @Test fun rejectsUnverifiedIncompleteAndOutOfBudgetMetadata() {
        val valid=metadata()
        val invalid=listOf(valid.copy(parsed=false),valid.copy(renderVerified=false),valid.copy(inspectorProtocolVersion=2),
            valid.copy(libpagVersion=""),valid.copy(fileVersion=2),valid.copy(width=513),valid.copy(height=513),valid.copy(width=0),
            valid.copy(durationMs=0),valid.copy(durationMs=3001),valid.copy(frameRate=60.01),valid.copy(frameRate=0.0),
            valid.copy(frameRate=Double.NaN),valid.copy(hasAudio=true),valid.copy(videoCount=1),valid.copy(videoCount=-1),
            valid.copy(layerCount=129),valid.copy(maxDepth=17),valid.copy(imageCount=33),valid.copy(imageCount=1),
            valid.copy(maxImageWidth=2049),valid.copy(maxRenderTimeMs=250.1),valid.copy(maxRenderTimeMs=Double.POSITIVE_INFINITY))
        invalid.forEach { invalidMetadata -> reject { StickerArchive.unpackV2(pack(),PagInspector { invalidMetadata }) } }
        reject { PagValidation.decode("{}") }
        reject { PagValidation.decode("{\"width\":512}") }
    }
    @Test fun validatesSizeBeforeCallingInspectorAndKeepsProfilesDistinct() {
        var called=false
        reject { StickerArchive.unpackV2(pack(pag=ByteArray(StickerRules.MAX_IMAGE+1)),PagInspector { called=true; metadata() }) }
        assertFalse(called)
        val effect=metadata().copy(width=2048,height=2048,durationMs=8000)
        PagValidation.inspect(ByteArray(2*1024*1024),PagValidation.EFFECT,PagInspector { effect })
        reject { PagValidation.inspect(ByteArray(2*1024*1024+1),PagValidation.EFFECT,PagInspector { effect }) }
        reject { PagValidation.inspect(fixture,PagValidation.STICKER,PagInspector { effect }) }
    }
}
