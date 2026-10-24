package sticker.logic

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import neton.core.http.NotFoundException
import neton.database.api.Row
import neton.storage.StorageNotFoundException
import neton.storage.StorageOperator
import neton.storage.WriteOptions
import sticker.dto.*
import sticker.table.StickerTable
import kotlin.random.Random
import kotlin.time.Clock

internal class StickerDelivery(private val table: StickerTable, private val storage: () -> StorageOperator) {
    private val json = Json { encodeDefaults = true }
    private fun now() = Clock.System.now().toEpochMilliseconds()
    private fun hash(bytes: ByteArray) = CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun missing(): Nothing = throw NotFoundException("STICKER_NOT_FOUND")
    private suspend fun snapshot(id: Long) = table.rows("SELECT * FROM sticker_archive_snapshots WHERE pack_version_id=:id", "id" to id).firstOrNull()

    // Caller holds authors + pack locks. No external writes, so publication rollback is atomic.
    suspend fun freeze(pack: Row): Row {
        val id = pack.long("id")
        snapshot(id)?.let { return it }
        val entries = table.rows("SELECT e.*,r.width,r.height,l.pag_resource_id FROM sticker_entries e JOIN sticker_resources r ON r.id=e.resource_id LEFT JOIN sticker_pag_links l ON l.fallback_resource_id=r.id WHERE e.pack_version_id=:id ORDER BY e.position", "id" to id)
        val resources = table.rows("SELECT r.* FROM sticker_resources r WHERE r.id=:cover OR r.id IN (SELECT resource_id FROM sticker_entries WHERE pack_version_id=:id) OR r.id IN (SELECT l.pag_resource_id FROM sticker_pag_links l JOIN sticker_entries e ON e.resource_id=l.fallback_resource_id WHERE e.pack_version_id=:id) ORDER BY r.id", "cover" to pack.longOrNull("cover_id"), "id" to id)
        StickerRules.check(entries.isNotEmpty() && resources.size < StoredStickerZip.MAX_ENTRIES, "archive_entries")
        val files = resources.map { r ->
            StickerRules.check(r.int("ready") == 1 && r.long("size") in 1..StoredStickerZip.MAX_BYTES.toLong(), "archive_source")
            val mime = r.string("mime_type")
            val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; "application/octet-stream" -> if (r.stringOrNull("pag_profile") == PagValidation.STICKER) "pag" else ""; else -> "" }
            StickerRules.check(ext.isNotEmpty(), "archive_mime")
            ArchiveResourceFile(r.long("id").toString(), "r_${r.long("id")}.$ext", r.string("digest"), r.long("size"), if (ext == "pag") "pag" else "image", mime)
        }
        val byId = files.associateBy { it.resourceId.toLong() }
        val author = (table.author(pack.long("author_id")) ?: missing()).string("name")
        val names = json.decodeFromString<Map<String, String>>(pack.string("name_json")).entries.sortedBy { it.key }.associate { it.key to it.value }
        val cover = byId[pack.longOrNull("cover_id")]?.file ?: missing()
        val schema = if (entries.any { it.longOrNull("pag_resource_id") != null }) 2 else 1
        val manifest = if (schema == 2) json.encodeToString(ManifestV2(2, pack.string("pack_id"), names, author, pack.int("version"), cover,
            entries.map { e -> ManifestEntryV2(e.string("key"), byId.getValue(e.long("resource_id")).file, e.string("emoji"), e.int("width"), e.int("height"), e.longOrNull("pag_resource_id")?.let { ManifestPag(byId.getValue(it).file, PagValidation.STICKER) }) }, pack.int("default_install")))
        else json.encodeToString(Manifest(pack.string("pack_id"), names, author, pack.int("version"), cover,
            entries.map { e -> ManifestEntry(e.string("key"), byId.getValue(e.long("resource_id")).file, e.string("emoji"), e.int("width"), e.int("height")) }, defaultInstall = pack.int("default_install")))
        val manifestBytes = manifest.encodeToByteArray()
        val zip = build(manifestBytes, files)
        val descriptor = ArchiveDescriptor(1, "zip-stored-v1", id.toString(), pack.string("pack_id"), pack.int("version"), schema,
            "/sticker/archive/$id/download", hash(zip), zip.size.toLong(), ArchiveManifestFile("manifest.json", hash(manifestBytes), manifestBytes.size.toLong()), files)
        return table.rows("INSERT INTO sticker_archive_snapshots(pack_version_id,manifest_json,descriptor_json,created_at) VALUES(:pack,:manifest,:descriptor,:now) RETURNING *",
            "pack" to id, "manifest" to manifest, "descriptor" to json.encodeToString(descriptor), "now" to now()).single()
    }

    private suspend fun build(manifest: ByteArray, files: List<ArchiveResourceFile>): ByteArray {
        StickerRules.check(manifest.size <= StoredStickerZip.MAX_MANIFEST && files.size < StoredStickerZip.MAX_ENTRIES && files.sumOf { it.size } + manifest.size <= StoredStickerZip.MAX_BYTES, "archive_size")
        StoredStickerZip.encodedSize(listOf("manifest.json" to manifest.size.toLong()) + files.map { it.file to it.size })
        val parts = mutableListOf("manifest.json" to manifest)
        for (file in files) {
            val r = table.resource(StickerRules.id(file.resourceId)) ?: missing()
            StickerRules.check(r.int("ready") == 1 && r.string("digest") == file.sha256 && r.long("size") == file.size && r.string("mime_type") == file.mimeType, "archive_source")
            val source = storage()
            StickerRules.check(source.exists(r.string("object_key")) && source.stat(r.string("object_key")).size == file.size, "archive_source")
            val bytes = source.read(r.string("object_key"))
            StickerRules.check(bytes.size.toLong() == file.size && hash(bytes) == file.sha256, "archive_source_digest")
            parts += file.file to bytes
        }
        return StoredStickerZip.write(parts)
    }

    private suspend fun authorize(member: Long, id: Long): Row {
        val initial = table.pack(id) ?: missing()
        table.lock("member:$member"); table.lock("pack:${initial.string("pack_id")}")
        val row = table.pack(id) ?: missing()
        if (row.int("publication_state") != StickerRules.PUBLISHED &&
            (row.int("publication_state") != StickerRules.OFFLINE || table.rows("SELECT id FROM sticker_installs WHERE member_id=:member AND pack_id=:pack AND status=1", "member" to member, "pack" to row.string("pack_id")).isEmpty())) missing()
        return row
    }

    suspend fun descriptor(member: Long, value: String): ArchiveDescriptor = table.db.transaction {
        table.lock("authors")
        val row = authorize(member, StickerRules.id(value))
        val descriptor = json.decodeFromString<ArchiveDescriptor>(freeze(row).string("descriptor_json"))
        StickerRules.check(descriptor.size in 1..StoredStickerZip.MAX_BYTES.toLong(), "archive_size")
        descriptor
    }

    suspend fun download(member: Long, value: String): Pair<ArchiveDescriptor, ByteArray> {
        val descriptor = descriptor(member, value)
        // Reservation survives rollback of the subsequent external write / cache pointer update.
        val reservation = table.db.transaction {
            authorize(member, StickerRules.id(value))
            val nonce = Random.nextBytes(24).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            table.rows("INSERT INTO sticker_archive_blobs(object_key,created_at) VALUES(:key,:now) RETURNING *", "key" to "platform/sticker-archives/$nonce.zip", "now" to now()).single()
        }
        val bytes = table.db.transaction {
            authorize(member, StickerRules.id(value))
            table.lock("archive-cache")
            // A request delayed beyond the orphan retention window must not write an untracked object.
            StickerRules.check(table.rows("SELECT id FROM sticker_archive_blobs WHERE id=:id", "id" to reservation.long("id")).isNotEmpty(), "archive_reservation_expired")
            val frozen = snapshot(StickerRules.id(value)) ?: missing()
            val cache = frozen.longOrNull("cache_blob_id")?.let { blob -> table.rows("SELECT * FROM sticker_archive_blobs WHERE id=:id", "id" to blob).firstOrNull() }
            val cached = cache?.let { readCache(it.string("object_key"), descriptor) }
            if (cached != null) {
                table.execute("DELETE FROM sticker_archive_blobs WHERE id=:id", "id" to reservation.long("id"))
                cached
            } else {
                val rebuilt = build(frozen.string("manifest_json").encodeToByteArray(), descriptor.files)
                StickerRules.check(rebuilt.size.toLong() == descriptor.size && hash(rebuilt) == descriptor.sha256, "archive_snapshot_digest")
                storage().write(reservation.string("object_key"), rebuilt, WriteOptions(overwrite = false))
                StickerRules.check(readCache(reservation.string("object_key"), descriptor) != null, "archive_cache_digest")
                table.execute("UPDATE sticker_archive_snapshots SET cache_blob_id=:blob WHERE id=:id", "blob" to reservation.long("id"), "id" to frozen.long("id"))
                rebuilt
            }
        }
        return descriptor to bytes
    }

    private suspend fun readCache(key: String, descriptor: ArchiveDescriptor): ByteArray? = try {
        val source = storage()
        if (!source.exists(key) || source.stat(key).size != descriptor.size) null
        else source.read(key).takeIf { it.size.toLong() == descriptor.size && hash(it) == descriptor.sha256 }
    } catch (_: StorageNotFoundException) { null }

    suspend fun cleanup(): Int = table.db.transaction {
        table.lock("archive-cache")
        val unused = table.rows("SELECT b.* FROM sticker_archive_blobs b WHERE b.created_at<:cutoff AND NOT EXISTS(SELECT 1 FROM sticker_archive_snapshots s WHERE s.cache_blob_id=b.id) ORDER BY b.id LIMIT 100", "cutoff" to now() - 86400000)
        for (blob in unused) {
            storage().delete(blob.string("object_key"))
            table.execute("DELETE FROM sticker_archive_blobs WHERE id=:id", "id" to blob.long("id"))
        }
        unused.size
    }
}
