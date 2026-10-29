// Test fixture opens the database directly; that entry point is an internal Neton API.
@file:OptIn(neton.core.annotations.InternalNetonApi::class)

import init.generated.StickerMigrationResources
import kotlinx.coroutines.*
import neton.core.component.NetonContext
import neton.core.config.getEnv
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.config.*
import neton.storage.*
import sticker.dto.*
import sticker.logic.*
import sticker.table.StickerTable
import kotlin.test.*
import kotlin.io.encoding.Base64

class StickerPostgresTest {
    @Test fun realPostgresAndLocalStorageLifecycle() = runBlocking<Unit> {
        val uri=getEnv("STICKER_TEST_DATABASE_URI") ?: return@runBlocking
        val config=DatabaseConfig(DatabaseDriver.POSTGRESQL,uri)
        val info=config.parseUri() as PostgresUriInfo
        require(info.host=="127.0.0.1" && info.port==55473 && info.database in setOf("sticker_test","sticker_test_lifecycle","sticker_test_immutable")) { "Only disposable loopback sticker test databases on port 55473 are allowed" }
        val db=SqlxDatabase.initialize(config)
        try {
            val occupied=db.fetchAll("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'sticker_%'")
            require(occupied.isEmpty()) { "Test requires an empty disposable schema; never wipes existing data" }
            // Production SQL, not a parallel SQLite/fake schema.
            val sql=StickerMigrationResources.sources.single().scripts.filter { it.description!="menus" }.joinToString("\n") { it.content }
            val statements=mutableListOf<String>(); val current=StringBuilder(); var quoted=false
            sql.forEach { c -> if(c=='\'') quoted=!quoted; if(c==';' && !quoted) { statements+=current.toString(); current.clear() } else current.append(c) }
            if(current.isNotBlank()) statements+=current.toString()
            statements.filter { it.isNotBlank() }.forEach { db.execute(it) }
            val ctx=NetonContext(emptyArray())
            StorageComponent.init(ctx,StorageConfig().apply {
                source("default") { basePath="/tmp/sticker-integration-storage" }
                source("sticker-private") { basePath="/tmp/sticker-integration-storage" }
            })
            val logic=StickerLogic(StickerTable(db),ctx.get(StorageManager::class),"http://127.0.0.1:55474")
            val author=logic.saveAuthor(AuthorWrite("Test author"),false)
            val bytes=Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
            // Valid 16x16 PNG from the frontend HTTP fixture, distinct from the v1 1x1 image.
            val newBytes=Base64.decode("iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAGUlEQVR4nGNQaDrxnxLMMGrAqAGjBgwXAwAj7GkfUAP6OAAAAABJRU5ErkJggg==")
            assertFalse(bytes.contentEquals(newBytes))
            assertEquals(16,StickerArchive.image(newBytes).width)
            val p=PackWrite("integration",mapOf("en" to "Test"),author.id,1)
            val empty=logic.savePack(p,1,false)
            assertFails { logic.transition(empty.id,2) }
            val made=logic.make(bytes,p,"smile",1)
            assertEquals(empty.id,made.id)
            val original=made.stickers.single().stickerId
            suspend fun storedDigest(id: String) = db.fetchAll("SELECT digest FROM sticker_resources WHERE id=:id",mapOf("id" to id.toLong())).single().string("digest")
            val oldDigest=storedDigest(original)
            assertEquals("431ced6916a2a21a156e38701afe55bbd7f88969fbbfc56d7fe099d47f265460",oldDigest)
            assertFails { logic.resolve(original) }
            assertFails { logic.bytes(original,false) }
            assertTrue(logic.store().isEmpty())
            assertFails { logic.install(1,p.packId) }
            val resourceCount=db.fetchAll("SELECT COUNT(*) AS n FROM sticker_resources").single().long("n")
            repeat(4) { assertEquals(made.id,logic.make(bytes,p,"smile",1).id) }
            assertEquals(resourceCount,db.fetchAll("SELECT COUNT(*) AS n FROM sticker_resources").single().long("n"),"Idempotent retries must not reserve orphan rows")
            assertFails { logic.make(bytes,p,"different",1) }
            val published=logic.transition(made.id,2)
            assertEquals(2,published.publicationState)
            assertTrue(logic.resolve(original).imageUrl.startsWith("http://127.0.0.1:55474/"))
            assertContentEquals(bytes,logic.bytes(original,false).first)
            assertFails { logic.savePack(p.copy(id=made.id),1,true) }
            assertFails { logic.deletePack(made.id) }
            coroutineScope { (1..4).map { async(Dispatchers.Default) { logic.install(1,p.packId) } }.awaitAll() }
            assertEquals(1,logic.installed(1).size)
            assertFails { logic.reorder(1,listOf(p.packId,p.packId)) }
            assertFails { logic.reorder(2,listOf(p.packId)) }
            val beforeConcurrent=db.fetchAll("SELECT COUNT(*) AS n FROM sticker_resources").single().long("n")
            val concurrent=coroutineScope { (1..4).map { async(Dispatchers.Default) { logic.make(newBytes,p.copy(version=2),"smile",1) } }.awaitAll() }
            val next=concurrent.first()
            assertTrue(concurrent.all { it.id==next.id })
            assertEquals(beforeConcurrent+1,db.fetchAll("SELECT COUNT(*) AS n FROM sticker_resources").single().long("n"),"Concurrent duplicate imports must clean losing reservations")
            val replacement=next.stickers.single().stickerId
            assertEquals(made.stickers.single().key,next.stickers.single().key)
            assertNotEquals(original,replacement)
            val newDigest=storedDigest(replacement)
            assertEquals("22ffe0483efa0a838192bac2b5e9caca210b85151ae9031facada54bf7666b02",newDigest)
            assertNotEquals(oldDigest,newDigest,"Same logical key with changed image must have a different persisted digest")
            suspend fun assertBothVersionsIntact(stage: String) {
                assertContentEquals(bytes,logic.bytes(original,false).first,"$stage: v1 must retain its original bytes")
                assertContentEquals(newBytes,logic.bytes(replacement,false).first,"$stage: v2 must return the replacement image")
                assertEquals(oldDigest,storedDigest(original),"$stage: old digest must not change")
                assertEquals(newDigest,storedDigest(replacement),"$stage: replacement digest must not change")
                assertEquals(original,logic.resolve(original).stickerId)
                assertEquals(replacement,logic.resolve(replacement).stickerId)
            }
            val outcomes=coroutineScope { (1..2).map { async(Dispatchers.Default) { runCatching { logic.transition(next.id,2) } } }.awaitAll() }
            assertEquals(1,outcomes.count { it.isSuccess })
            assertEquals(3,logic.pack(made.id).publicationState)
            assertBothVersionsIntact("after v2 publication")
            assertEquals(2,logic.installed(1).single().version)
            val authorsHeld=CompletableDeferred<Unit>(); val releaseAuthors=CompletableDeferred<Unit>()
            val holder=async(Dispatchers.Default) { db.transaction {
                StickerTable(db).lock("authors"); authorsHeld.complete(Unit); releaseAuthors.await()
            } }
            authorsHeld.await()
            try { withTimeout(2000) { logic.transition(next.id,3) } }
            finally { releaseAuthors.complete(Unit); holder.await() }
            assertFails { logic.install(2,p.packId) }
            assertEquals(1,logic.installed(1).size)
            assertEquals(2,logic.installed(1).single().version,"Offline keeps the latest ever-published version installed")
            assertBothVersionsIntact("after v2 offline")
            logic.uninstall(1,p.packId)
            assertTrue(logic.installed(1).isEmpty())
            assertFails { logic.deleteAuthor(author.id) }
            db.execute("UPDATE sticker_resources SET created_at=0")
            logic.cleanup()
            assertBothVersionsIntact("after cleanup")
            // Disabled author cannot create another draft.
            logic.saveAuthor(AuthorWrite(author.name,status=0,id=author.id),true)
            assertFails { logic.savePack(p.copy(version=3),1,false) }
            println("STICKER_IMMUTABLE_REPLACEMENT_EXECUTED: different PNG bytes and digests preserved across publish/offline/cleanup")
            println("STICKER_POSTGRES_EXECUTED: real DB + StorageOperator lifecycle/concurrency passed")
        } finally { SqlxDatabase.close() }
    }
}
