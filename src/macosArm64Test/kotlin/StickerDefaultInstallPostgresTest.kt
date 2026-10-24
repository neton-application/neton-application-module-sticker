@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
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

class StickerDefaultInstallPostgresTest {
    @Test fun defaultsExplicitOptOutOrderingAndHistoryAreAccountScoped() = runBlocking {
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
            require(db.fetchAll("SELECT COUNT(*) AS n FROM sticker_packs").single().long("n") == 0L) { "Requires fresh migrated disposable DB; never wipes" }
            val ctx = NetonContext(emptyArray())
            StorageComponent.init(ctx, StorageConfig().apply {
                source("default") { basePath = "$root/storage" }
                source("sticker-private") { basePath = "$root/storage" }
            })
            fun logic() = StickerLogic(StickerTable(db), ctx.get(StorageManager::class), "http://127.0.0.1:55479")
            val service = logic()
            val author = service.saveAuthor(AuthorWrite("Original pack acceptance"), false)
            suspend fun publish(key: String, flag: Int, version: Int = 1): PackDto {
                val draft = service.make(bytes, PackWrite(key, mapOf("en" to key), author.id, version, defaultInstall = flag), "first", 9001)
                assertEquals(flag, draft.defaultInstall)
                assertEquals("/admin/sticker/resource/get/${draft.coverId}", draft.coverImageUrl)
                return service.transition(draft.id, StickerRules.PUBLISHED)
            }
            val smile = publish("smileys", 1)
            val animals = publish("animals", 0)
            val objects = publish("objects", 0)
            assertEquals(3, service.store().size)
            assertTrue(service.store().all { it.coverImageUrl == "http://127.0.0.1:55479/app/sticker/resource/get/${it.coverId}" })
            assertTrue(Json.encodeToString(service.store()).contains("\"defaultInstall\":0"))
            assertEquals(listOf("smileys"), service.installed(9201).map { it.packId })
            assertEquals(listOf("smileys"), service.installed(9202).map { it.packId })
            service.uninstall(9201, "smileys")
            repeat(3) { assertTrue(logic().installed(9201).isEmpty()) }
            assertEquals(listOf("smileys"), logic().installed(9202).map { it.packId })
            service.install(9201, "animals"); service.install(9201, "objects")
            service.reorder(9201, listOf("objects", "animals"))
            service.install(9201, "objects")
            assertEquals(listOf("objects", "animals"), logic().installed(9201).map { it.packId })
            assertFails { service.reorder(9201, listOf("smileys", "objects", "animals")) }
            assertFails { service.reorder(9201, listOf("animals")) }
            assertFails { service.reorder(9201, listOf("objects", "objects")) }
            assertFails { service.savePack(PackWrite("invalid", mapOf("en" to "Invalid"), author.id, 1, defaultInstall = 2), 9001, false) }
            assertFails { service.savePack(PackWrite("smileys", smile.name, author.id, 1, smile.id, 0), 9001, true) }
            publish("smileys", 1, 2)
            assertEquals(listOf("objects", "animals"), logic().installed(9201).map { it.packId })
            assertEquals(2, logic().installed(9202).single().version)
            service.install(9201, "smileys")
            assertEquals(listOf("objects", "animals", "smileys"), service.installed(9201).map { it.packId })
            service.transition(animals.id, StickerRules.OFFLINE)
            assertTrue(service.installed(9201).any { it.packId == "animals" })
            assertTrue(service.store().none { it.packId == "animals" })
            assertEquals(animals.stickers.single().stickerId, service.resolve(animals.stickers.single().stickerId).stickerId)
            service.uninstall(9201, "objects")
            assertEquals(objects.stickers.single().stickerId, service.resolve(objects.stickers.single().stickerId).stickerId)
            coroutineScope { List(4) { async(Dispatchers.Default) { logic().installed(9203) } }.awaitAll() }
            assertEquals(1L, db.fetchAll("SELECT COUNT(*) AS n FROM sticker_installs WHERE member_id=9203").single().long("n"))
            println("STICKER_DEFAULT_PG_EXECUTED: real PG/storage + defaults/opt-out/version/reinstall/reorder/account isolation/history/concurrent initialization")
        } finally { SqlxDatabase.close() }
    }
}
