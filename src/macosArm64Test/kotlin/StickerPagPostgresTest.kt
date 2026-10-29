// Test fixture opens the database directly; that entry point is an internal Neton API.
@file:OptIn(neton.core.annotations.InternalNetonApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
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

/** Opt-in real official CLI + PostgreSQL + local StorageOperator; no metadata test double. */
class StickerPagPostgresTest {
    @Test fun officialPagImportPublicationAndHistoricalFallback() = runBlocking {
        val uri=getEnv("PAG_TEST_DATABASE_URI") ?: return@runBlocking
        val config=DatabaseConfig(DatabaseDriver.POSTGRESQL,uri)
        val info=config.parseUri() as PostgresUriInfo
        require(info.host=="127.0.0.1" && info.port==55476 && (info.database=="pag_backend_test" || info.database.startsWith("pag_backend_test_")))
        val root=getEnv("PAG_TEST_STORAGE_ROOT") ?: error("PAG_TEST_STORAGE_ROOT required")
        require(root.startsWith("/tmp/pag-postgres."))
        val cli=getEnv("PAG_TEST_INSPECTOR") ?: error("Official CLI path required")
        val zip=readAcceptanceFile(getEnv("PAG_TEST_STICKER_ZIP") ?: error("Real v2 ZIP required"))
        val db=SqlxDatabase.initialize(config)
        try {
            require(db.fetchAll("SELECT COUNT(*) AS n FROM sticker_authors").single().long("n")==0L) { "Requires empty pre-migrated disposable sticker tables; never wipes data" }
            val ctx=NetonContext(emptyArray())
            StorageComponent.init(ctx,StorageConfig().apply {
                source("default") { basePath="$root/storage" }
                source("sticker-private") { basePath="$root/storage" }
            })
            val storage=ctx.get(StorageManager::class)
            val logic=StickerLogic(StickerTable(db),storage,"http://127.0.0.1:55477",configuredPagInspector(cli))
            val author=logic.saveAuthor(AuthorWrite("PAG acceptance"),false)
            assertFails { StickerLogic(StickerTable(db),storage,"http://127.0.0.1:55477").importZipV2(zip,author.id,1) }
            assertEquals(0,db.fetchAll("SELECT COUNT(*) AS n FROM sticker_resources").single().long("n"))
            val draft=logic.importZipV2(zip,author.id,1)
            val fallback=draft.stickers.single()
            val pag=assertNotNull(fallback.pag)
            assertEquals("pag-sticker-v1",pag.profile)
            assertEquals(256,pag.width)
            assertEquals(256,pag.height)
            assertEquals(2400L,pag.durationMs)
            assertEquals(256,fallback.width)
            assertEquals(256,fallback.height)
            assertNotEquals(fallback.stickerId,pag.resourceId)
            assertTrue(fallback.imageUrl.endsWith(fallback.stickerId))
            assertFails { logic.resolve(fallback.stickerId) }
            assertFails { logic.bytes(pag.resourceId,false) }
            val originalFallback=logic.bytes(fallback.stickerId,true).first
            val originalPag=logic.bytes(pag.resourceId,true).first
            assertEquals(draft.id,logic.importZipV2(zip,author.id,1).id)
            assertEquals(2,db.fetchAll("SELECT COUNT(*) AS n FROM sticker_resources").single().long("n"))
            logic.transition(draft.id,StickerRules.PUBLISHED)
            assertEquals(pag.resourceId,logic.resolve(fallback.stickerId).pag?.resourceId)
            assertFails { logic.resolve(pag.resourceId) }
            assertContentEquals(originalPag,logic.bytes(pag.resourceId,false).first)
            logic.transition(draft.id,StickerRules.OFFLINE)
            logic.cleanup()
            assertContentEquals(originalFallback,logic.bytes(fallback.stickerId,false).first)
            assertContentEquals(originalPag,logic.bytes(pag.resourceId,false).first)
            assertEquals(pag.resourceId,logic.resolve(fallback.stickerId).pag?.resourceId)
            // A pre-existing oversized draft is intentionally NOT subjected to new ingress checks.
            val legacy=readAcceptanceFile(getEnv("PAG_TEST_LEGACY_PNG") ?: error("Real 513px PNG required"))
            assertFails { StickerArchive.image(legacy) }
            val old=logic.savePack(PackWrite("legacy-pag-test",mapOf("en" to "Legacy"),author.id,1),1,false)
            storage.get("sticker-private").write("legacy/513.png",legacy,WriteOptions(overwrite=false))
            val id=db.fetchAll("INSERT INTO sticker_resources(object_key,digest,mime_type,width,height,size,author_id,operator_id,created_at,ready) VALUES('legacy/513.png',:digest,'image/png',513,1,:size,:author,1,0,1) RETURNING id",mapOf("digest" to "0".repeat(64),"size" to legacy.size.toLong(),"author" to author.id.toLong())).single().long("id")
            db.execute("INSERT INTO sticker_entries(pack_version_id,resource_id,key,position) VALUES(:pack,:resource,'old',0)",mapOf("pack" to old.id.toLong(),"resource" to id))
            db.execute("UPDATE sticker_packs SET cover_id=:resource WHERE id=:pack",mapOf("resource" to id,"pack" to old.id.toLong()))
            logic.transition(old.id,StickerRules.PUBLISHED)
            assertEquals(513,logic.resolve(id.toString()).width)
            assertContentEquals(legacy,logic.bytes(id.toString(),false).first)
            println("STICKER_PAG_POSTGRES_EXECUTED: official libpag + real PG/storage + immutable fallback/PAG + legacy 513px publish/read")
        } finally { SqlxDatabase.close() }
    }
}

private fun readAcceptanceFile(path: String): ByteArray {
    val file=fopen(path,"rb") ?: error("Cannot open acceptance file")
    try {
        check(fseek(file,0,SEEK_END)==0); val size=ftell(file)
        require(size in 1..StickerRules.MAX_PACKAGE.toLong()); rewind(file)
        return ByteArray(size.toInt()).also { bytes -> bytes.usePinned { check(fread(it.addressOf(0),1u,bytes.size.convert(),file).toLong()==size) } }
    } finally { fclose(file) }
}
