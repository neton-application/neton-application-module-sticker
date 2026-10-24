package sticker.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Trusted server port, never a request-provided executable or metadata assertion. */
fun interface PagInspector {
    fun inspect(bytes: ByteArray): PagMetadata
}

@Serializable
data class PagMetadata(
    val inspectorProtocolVersion: Int,
    val libpagVersion: String,
    val parsed: Boolean,
    val renderVerified: Boolean,
    val fileVersion: Int,
    val supportedFileVersion: Int,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val frameRate: Double,
    val hasAudio: Boolean,
    val videoCount: Int,
    val layerCount: Int,
    val maxDepth: Int,
    val imageCount: Int,
    val maxImageWidth: Int,
    val maxImageHeight: Int,
    val maxRenderTimeMs: Double,
)

object PagValidation {
    const val STICKER = "pag-sticker-v1"
    const val EFFECT = "pag-effect-v1"
    val unavailable = PagInspector { throw neton.core.http.BadRequestException("STICKER_PAG_INSPECTOR_UNAVAILABLE") }

    fun decode(output: String): PagMetadata = try {
        Json.decodeFromString<PagMetadata>(output)
    } catch (_: kotlinx.serialization.SerializationException) {
        throw neton.core.http.BadRequestException("STICKER_PAG_INVALID_INSPECTION")
    }

    @Serializable
    private data class OfficialInspection(val valid: Boolean, val sdkVersion: String, val bytes: Int,
        val width: Int, val height: Int, val durationMs: Long, val frameRate: Double, val hasAudio: Boolean,
        val numVideos: Int, val numImages: Int, val numTexts: Int, val tagLevel: Int, val maxSupportedTagLevel: Int,
        val layerCount: Int, val maxDepth: Int, val truncated: Boolean, val sha256: String, val profile: String,
        val renderProbe: Boolean, val probeFrameMs: List<Double>, val probeMaxFrameMs: Double,
        val codecParsed: Boolean, val codecHasAudio: Boolean, val codecLayerCount: Int, val codecTruncated: Boolean,
        val compositionBoundsValid: Boolean, val compositionCount: Int, val maxCompositionWidth: Int,
        val maxCompositionHeight: Int, val maxCompositionDurationMs: Long, val embeddedImageCount: Int,
        val imageLayerCount: Int, val textLayerCount: Int, val bitmapCompositions: Int, val videoCompositions: Int)

    fun decodeOfficial(output: String, usage: String, byteCount: Int): PagMetadata {
        val m=try { Json.decodeFromString<OfficialInspection>(output) }
        catch (_: kotlinx.serialization.SerializationException) { throw neton.core.http.BadRequestException("STICKER_PAG_INVALID_INSPECTION") }
        StickerRules.check(m.valid && m.sdkVersion=="4.5.98" && m.profile==usage && m.bytes==byteCount &&
            Regex("[0-9a-f]{64}").matches(m.sha256) && !m.truncated && m.renderProbe &&
            m.probeFrameMs.size==3 && m.probeFrameMs.all { it.isFinite() && it>=0 },"pag_unverified")
        val axis=if(usage=="sticker") 512 else 2048
        val maxDuration=if(usage=="sticker") 3000L else 8000L
        StickerRules.check(m.codecParsed && !m.codecHasAudio && !m.codecTruncated && m.codecLayerCount in 1..128 &&
            m.compositionBoundsValid && m.compositionCount in 1..128 &&
            m.maxCompositionWidth in 1..axis && m.maxCompositionHeight in 1..axis &&
            m.maxCompositionDurationMs in 1..maxDuration,"pag_composition_budget")
        // The initial official CLI does not expose bitmap bounds or font dependencies.
        // Reject those compositions rather than inventing safe texture/font metadata.
        StickerRules.check(m.numImages==0 && m.numTexts==0 && m.embeddedImageCount==0 && m.imageLayerCount==0 &&
            m.textLayerCount==0 && m.bitmapCompositions==0 && m.videoCompositions==0 && m.maxDepth in 0..8,"pag_complexity")
        StickerRules.check(m.probeMaxFrameMs.isFinite() && kotlin.math.abs(m.probeMaxFrameMs-m.probeFrameMs.max())<0.001,"pag_render_budget")
        return PagMetadata(1,m.sdkVersion,true,true,m.tagLevel,m.maxSupportedTagLevel,m.width,m.height,m.durationMs,
            m.frameRate,m.hasAudio,m.numVideos,m.layerCount,m.maxDepth+1,0,0,0,m.probeFrameMs.max())
    }

    fun inspect(bytes: ByteArray, profile: String, inspector: PagInspector): PagMetadata {
        StickerRules.check(profile == STICKER || profile == EFFECT, "pag_profile")
        val sticker = profile == STICKER
        StickerRules.check(bytes.size in 1..(if (sticker) StickerRules.MAX_IMAGE else 2 * 1024 * 1024), "pag_size")
        val m = inspector.inspect(bytes)
        StickerRules.check(m.inspectorProtocolVersion == 1 && m.libpagVersion.isNotBlank() && m.parsed && m.renderVerified,
            "pag_unverified")
        StickerRules.check(m.fileVersion > 0 && m.supportedFileVersion >= m.fileVersion, "pag_version")
        val maxDimension = if (sticker) 512 else 2048
        StickerRules.check(m.width in 1..maxDimension && m.height in 1..maxDimension, "pag_dimensions")
        StickerRules.check(m.durationMs in 1..(if (sticker) 3000L else 8000L), "pag_duration")
        StickerRules.check(m.frameRate.isFinite() && m.frameRate >= 1 && m.frameRate <= 60, "pag_frame_rate")
        StickerRules.check(!m.hasAudio && m.videoCount == 0, "pag_media_budget")
        // Conservative initial publication profile; widening requires measured client acceptance.
        StickerRules.check(m.layerCount in 1..128 && m.maxDepth in 1..16 && m.imageCount in 0..32 &&
            m.maxImageWidth in 0..2048 && m.maxImageHeight in 0..2048 &&
            (m.imageCount == 0 || (m.maxImageWidth > 0 && m.maxImageHeight > 0)), "pag_complexity")
        StickerRules.check(m.maxRenderTimeMs.isFinite() && m.maxRenderTimeMs >= 0 && m.maxRenderTimeMs <= 250,
            "pag_render_budget")
        return m
    }
}

expect fun configuredPagInspector(executable: String?, usage: String = "sticker"): PagInspector
