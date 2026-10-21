@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package sticker.logic
import kotlinx.cinterop.*
import platform.zlib.*

internal actual fun inflateRaw(input: ByteArray, expected: Int): ByteArray = memScoped {
    StickerRules.check(input.size in 1..StickerArchive.MAX_COMPRESSED && expected in 1..StickerRules.MAX_IMAGE.coerceAtLeast(128*1024), "zip_entry_size")
    val stream=alloc<z_stream>()
    platform.posix.memset(stream.ptr,0,sizeOf<z_stream>().convert())
    StickerRules.check(inflateInit2_(stream.ptr,-15,zlibVersion(),sizeOf<z_stream>().toInt())==Z_OK,"zip_inflate")
    try {
        val output=ByteArray(expected)
        input.usePinned { source -> output.usePinned { target ->
            stream.next_in=source.addressOf(0).reinterpret(); stream.avail_in=input.size.toUInt()
            stream.next_out=target.addressOf(0).reinterpret(); stream.avail_out=output.size.toUInt()
            val result=inflate(stream.ptr,Z_FINISH)
            StickerRules.check(result==Z_STREAM_END && stream.total_out.toLong()==expected.toLong() && stream.avail_in==0u,"zip_inflate")
        } }
        output
    } finally { inflateEnd(stream.ptr) }
}
