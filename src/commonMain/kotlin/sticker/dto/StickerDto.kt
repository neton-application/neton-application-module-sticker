package sticker.dto
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable data class IdRequest(val id: String)
@Serializable data class AuthorWrite(val name: String, val bio: String = "", val status: Int = 1, val id: String? = null)
@Serializable data class AuthorDto(val id: String, val name: String, val bio: String, val status: Int)
@Serializable data class PackWrite(val packId: String, val name: Map<String, String>, val authorId: String, val version: Int, val id: String? = null)
@Serializable data class PackDto(val id: String, val packId: String, val name: Map<String, String>, val authorId: String, val version: Int, val publicationState: Int, val stickers: List<StickerDto>, val coverId: String?, val createdAt: Long)
@Serializable data class StickerDto(val stickerId: String, val key: String, val emoji: String, val width: Int, val height: Int, val mimeType: String, val size: Long, val imageUrl: String)
@Serializable data class Manifest(@SerialName("pack_id") val packId: String, val name: Map<String, String>, val author: String, val version: Int, val cover: String, val stickers: List<ManifestEntry>)
@Serializable data class ManifestEntry(val key: String, val file: String, val emoji: String = "", val width: Int, val height: Int)
@Serializable data class InstallRequest(val packId: String)
@Serializable data class ReorderRequest(val packIds: List<String>)
