@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package sticker.dto
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable data class IdRequest(val id: String)
@Serializable data class AuthorWrite(val name: String, val bio: String = "", val status: Int = 1, val id: String? = null)
@Serializable data class AuthorDto(val id: String, val name: String, val bio: String, val status: Int)
@Serializable data class PackWrite(val packId: String, val name: Map<String, String>, val authorId: String, val version: Int, val id: String? = null, val defaultInstall: Int = 0)
@Serializable data class PackDto(val id: String, val packId: String, val name: Map<String, String>, val authorId: String, val version: Int, val publicationState: Int, val stickers: List<StickerDto>, val coverId: String?, val createdAt: Long, @kotlinx.serialization.EncodeDefault val coverImageUrl: String? = null, @kotlinx.serialization.EncodeDefault val defaultInstall: Int = 0, @kotlinx.serialization.EncodeDefault val versionRowId: String? = null)
@Serializable data class StickerDto(val stickerId: String, val key: String, val emoji: String, val width: Int, val height: Int, val mimeType: String, val size: Long, val imageUrl: String, val pag: PagDescriptor? = null)
@Serializable data class PagDescriptor(val resourceId: String, val url: String, val sha256: String, val size: Long, val width: Int, val height: Int, val durationMs: Long, val profile: String = "pag-sticker-v1")
@Serializable data class ManifestV2(@SerialName("schema_version") val schemaVersion: Int, @SerialName("pack_id") val packId: String, val name: Map<String, String>, val author: String, val version: Int, val cover: String, val stickers: List<ManifestEntryV2>, @SerialName("default_install") val defaultInstall: Int = 0)
@Serializable data class ManifestEntryV2(val key: String, val file: String, val emoji: String = "", val width: Int, val height: Int, val pag: ManifestPag? = null)
@Serializable data class ManifestPag(val file: String, val profile: String)
@Serializable data class Manifest(@SerialName("pack_id") val packId: String, val name: Map<String, String>, val author: String, val version: Int, val cover: String, val stickers: List<ManifestEntry>, @SerialName("schema_version") val schemaVersion: Int = 1, @SerialName("default_install") val defaultInstall: Int = 0)
@Serializable data class ManifestEntry(val key: String, val file: String, val emoji: String = "", val width: Int, val height: Int)
@Serializable data class InstallRequest(val packId: String, val versionRowId: String? = null)
@Serializable data class ReorderRequest(val packIds: List<String>)

@Serializable data class ArchiveManifestFile(val file: String, val sha256: String, val size: Long)
@Serializable data class ArchiveResourceFile(val resourceId: String, val file: String, val sha256: String, val size: Long, val kind: String, val mimeType: String)
@Serializable data class ArchiveDescriptor(val schemaVersion: Int, val format: String, val versionRowId: String, val packId: String, val version: Int, val manifestSchemaVersion: Int, val downloadPath: String, val sha256: String, val size: Long, val manifest: ArchiveManifestFile, val files: List<ArchiveResourceFile>)
