// Test fixture opens the database directly; that entry point is an internal Neton API.
@file:OptIn(neton.core.annotations.InternalNetonApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import neton.core.component.NetonContext
import neton.core.config.getEnv
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.config.*
import neton.storage.*
import platform.posix.*
import sticker.dto.*
import sticker.logic.*
import sticker.table.StickerTable
import kotlin.test.*

class StickerDeliveryPostgresTest {
    @Test fun immutableSnapshotsRecoveryAuthorizationAndRollback() = runBlocking {
        val uri = getEnv("STICKER_PACK_DATABASE_URI") ?: return@runBlocking
        val config = DatabaseConfig(DatabaseDriver.POSTGRESQL, uri)
        val info = config.parseUri() as PostgresUriInfo
        require(info.host == "127.0.0.1" && info.port == 55476 && info.database.startsWith("sticker_packs_"))
        val root = getEnv("STICKER_PACK_ROOT") ?: error("Private storage root required")
        require(root.startsWith("/tmp/sticker-packs."))
        val file = fopen(getEnv("STICKER_PACK_PNG") ?: error("Actual PNG required"), "rb") ?: error("PNG unavailable")
        val bytes = try {
            check(fseek(file, 0, SEEK_END) == 0); val size = ftell(file); require(size in 1..StickerRules.MAX_IMAGE.toLong()); rewind(file)
            ByteArray(size.toInt()).also { b -> b.usePinned { check(fread(it.addressOf(0), 1u, b.size.convert(), file).toLong() == size) } }
        } finally { fclose(file) }
        val db = SqlxDatabase.initialize(config)
        try {
            require(db.fetchAll("SELECT COUNT(*) AS n FROM sticker_packs").single().long("n") == 0L) { "Fresh disposable database required; never wipes" }
            val ctx = NetonContext(emptyArray())
            StorageComponent.init(ctx, StorageConfig().apply {
                source("default") { basePath = "$root/storage" }
                source("sticker-private") { basePath = "$root/storage" }
            })
            val manager = ctx.get(StorageManager::class)
            val storage = manager.get("sticker-private")
            fun service() = StickerLogic(StickerTable(db), manager, "http://127.0.0.1:55489")
            val logic = service()
            val author = logic.saveAuthor(AuthorWrite("Frozen author"), false)
            val draft = logic.make(bytes, PackWrite("archive-pg", mapOf("en" to "Archive"), author.id, 1), "first", 9001)
            assertFails { logic.archiveDescriptor(9101, draft.id) }
            assertFails { logic.archiveDownload(9101, draft.id) }
            assertEquals(0, db.fetchAll("SELECT * FROM sticker_archive_blobs").size)
            val published = logic.transition(draft.id, StickerRules.PUBLISHED)
            assertEquals(published.id, published.versionRowId)
            assertEquals(1, db.fetchAll("SELECT * FROM sticker_archive_snapshots").size)
            assertEquals(0, db.fetchAll("SELECT * FROM sticker_archive_blobs").size)
            val descriptor = logic.archiveDescriptor(9101, published.id)
            val first = logic.archiveDownload(9101, published.id).second
            assertEquals(first.size.toLong(), descriptor.size)
            assertEquals(1, descriptor.files.size) // cover and first sticker deduplicated
            assertEquals(published.coverId, descriptor.files.single().resourceId)
            val manifest = db.fetchAll("SELECT manifest_json FROM sticker_archive_snapshots").single().string("manifest_json")
            assertEquals("Frozen author", Json.decodeFromString<Manifest>(manifest).author)
            logic.saveAuthor(AuthorWrite("Renamed author", id = author.id), true)
            assertEquals(descriptor, service().archiveDescriptor(9102, published.id))
            suspend fun cacheKey() = db.fetchAll("SELECT b.object_key FROM sticker_archive_snapshots s JOIN sticker_archive_blobs b ON b.id=s.cache_blob_id WHERE s.pack_version_id=:id", mapOf("id" to published.id.toLong())).single().string("object_key")
            storage.delete(cacheKey())
            assertContentEquals(first, service().archiveDownload(9101, published.id).second)
            storage.write(cacheKey(), ByteArray(first.size) { 7 })
            assertContentEquals(first, service().archiveDownload(9101, published.id).second)
            assertEquals(manifest, db.fetchAll("SELECT manifest_json FROM sticker_archive_snapshots").single().string("manifest_json"))
            assertFails { db.execute("UPDATE sticker_archive_snapshots SET manifest_json='{}'") }
            val source = db.fetchAll("SELECT object_key FROM sticker_resources WHERE id=:id", mapOf("id" to published.coverId!!.toLong())).single().string("object_key")
            storage.delete(cacheKey()); storage.write(source, ByteArray(bytes.size) { 8 })
            assertFails { service().archiveDownload(9101, published.id) }
            storage.write(source, bytes)
            assertContentEquals(first, service().archiveDownload(9101, published.id).second)
            logic.install(9101, published.packId, published.id)
            logic.transition(published.id, StickerRules.OFFLINE)
            assertContentEquals(first, logic.archiveDownload(9101, published.id).second)
            assertFails { logic.archiveDescriptor(9102, published.id) }
            assertFails { logic.archiveDownload(9102, published.id) }
            assertFails { logic.install(9102, published.packId, published.id) }
            logic.uninstall(9101, published.packId)
            assertFails { logic.archiveDescriptor(9101, published.id) }

            val legacy = logic.make(bytes, PackWrite("legacy-pg", mapOf("en" to "Legacy"), author.id, 1), "first", 9001)
            // Emulate an already-published row from a pre-V005 binary, without modifying its bytes.
            db.execute("UPDATE sticker_packs SET publication_state=2 WHERE id=:id", mapOf("id" to legacy.id.toLong()))
            db.execute("UPDATE sticker_resources SET ever_published=1 WHERE id=:id", mapOf("id" to legacy.coverId!!.toLong()))
            repeat(2) { service().store(); service().installed(9102) }
            assertEquals(1, db.fetchAll("SELECT * FROM sticker_archive_snapshots").size)
            coroutineScope { List(4) { async(Dispatchers.Default) { service().archiveDownload(9102, legacy.id) } }.awaitAll().let { results -> results.forEach { assertContentEquals(results[0].second, it.second) } } }
            assertEquals(2, db.fetchAll("SELECT * FROM sticker_archive_snapshots").size)
            val next = logic.make(bytes, PackWrite(legacy.packId, legacy.name, author.id, 2), "first", 9001)
            logic.transition(next.id, StickerRules.PUBLISHED)
            assertFails { logic.install(9102, legacy.packId, legacy.id) }
            logic.install(9102, next.packId, next.id)

            val broken = logic.make(bytes, PackWrite("broken-pg", mapOf("en" to "Broken"), author.id, 1), "first", 9001)
            val brokenKey = db.fetchAll("SELECT object_key FROM sticker_resources WHERE id=:id", mapOf("id" to broken.coverId!!.toLong())).single().string("object_key")
            storage.write(brokenKey, ByteArray(bytes.size) { 9 })
            assertFails { logic.transition(broken.id, StickerRules.PUBLISHED) }
            assertEquals(StickerRules.DRAFT, logic.pack(broken.id).publicationState)
            assertEquals(0, db.fetchAll("SELECT * FROM sticker_archive_snapshots WHERE pack_version_id=:id", mapOf("id" to broken.id.toLong())).size)
            val failedWrite = logic.make(bytes, PackWrite("failed-cache-pg", mapOf("en" to "Failed write"), author.id, 1), "first", 9001)
            logic.transition(failedWrite.id, StickerRules.PUBLISHED)
            val interrupted = object : StorageOperator by storage {
                override suspend fun write(path: String, data: ByteArray, options: WriteOptions) {
                    storage.write(path, data.copyOf(10), options)
                    error("Injected failure after real partial private-object write")
                }
            }
            val faultManager = object : StorageManager by manager {
                override fun get(name: String): StorageOperator = if (name == "sticker-private") interrupted else manager.get(name)
            }
            assertFails { StickerLogic(StickerTable(db), faultManager, "http://127.0.0.1:55489").archiveDownload(9102, failedWrite.id) }
            assertNull(db.fetchAll("SELECT cache_blob_id FROM sticker_archive_snapshots WHERE pack_version_id=:id", mapOf("id" to failedWrite.id.toLong())).single().longOrNull("cache_blob_id"))
            assertTrue(db.fetchAll("SELECT b.object_key FROM sticker_archive_blobs b WHERE NOT EXISTS(SELECT 1 FROM sticker_archive_snapshots s WHERE s.cache_blob_id=b.id)").any { storage.exists(it.string("object_key")) })
            assertTrue(service().archiveDownload(9102, failedWrite.id).second.isNotEmpty())
            val expired = logic.make(bytes, PackWrite("expired-reservation-pg", mapOf("en" to "Expired reservation"), author.id, 1), "first", 9001)
            logic.transition(expired.id, StickerRules.PUBLISHED)
            val baseline = db.fetchAll("SELECT COALESCE(MAX(id),0) AS id FROM sticker_archive_blobs").single().long("id")
            val locked = CompletableDeferred<Unit>()
            val deletedKey = CompletableDeferred<String>()
            // Simulate cleanup winning while a downloader waits for the maintenance lock.
            coroutineScope {
                val gc = async(Dispatchers.Default) {
                    db.transaction {
                        StickerTable(db).lock("archive-cache"); locked.complete(Unit)
                        val reserved = withTimeout(5000) {
                            var row = db.fetchAll("SELECT * FROM sticker_archive_blobs WHERE id>:id ORDER BY id LIMIT 1", mapOf("id" to baseline)).firstOrNull()
                            while (row == null) { delay(5); row = db.fetchAll("SELECT * FROM sticker_archive_blobs WHERE id>:id ORDER BY id LIMIT 1", mapOf("id" to baseline)).firstOrNull() }
                            row
                        }
                        deletedKey.complete(reserved.string("object_key"))
                        db.execute("DELETE FROM sticker_archive_blobs WHERE id=:id", mapOf("id" to reserved.long("id")))
                    }
                }
                locked.await()
                val result = async(Dispatchers.Default) { runCatching { service().archiveDownload(9102, expired.id) } }
                gc.await()
                assertTrue(result.await().isFailure)
            }
            assertFalse(storage.exists(deletedKey.await()))
            assertTrue(service().archiveDownload(9102, expired.id).second.isNotEmpty())
            // Failed repairs leave durable unreferenced reservations; cleanup never removes live cache.
            db.execute("UPDATE sticker_archive_blobs SET created_at=0 WHERE id NOT IN (SELECT cache_blob_id FROM sticker_archive_snapshots WHERE cache_blob_id IS NOT NULL)")
            logic.cleanup()
            assertTrue(db.fetchAll("SELECT * FROM sticker_archive_blobs WHERE created_at=0").isEmpty())
            assertTrue(logic.archiveDownload(9102, next.id).second.isNotEmpty())
            println("STICKER_ARCHIVE_PG_EXECUTED: publication freeze, lazy legacy, rename, exact missing/corrupt ZIP repair, corrupt source fail-closed, rollback, concurrency, access, CAS and orphan cleanup")
        } finally { SqlxDatabase.close() }
    }
}
