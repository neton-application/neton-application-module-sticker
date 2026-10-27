package sticker.logic

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dto.PageResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import neton.core.http.BadRequestException
import neton.core.http.NotFoundException
import neton.core.http.HttpException
import neton.database.api.Row
import neton.storage.StorageManager
import sticker.dto.*
import sticker.table.StickerTable
import kotlin.random.Random
import kotlin.time.Clock

class StickerLogic(private val table: StickerTable, private val storageManager: StorageManager, private val publicBaseUrl: String, private val pagInspector: PagInspector = PagValidation.unavailable) {
    private val storage get() = storageManager.get("sticker-private").also { check(it.baseUrl.isBlank()) { "sticker-private must not have a public baseUrl" } }
    private val delivery = StickerDelivery(table) { storage }
    suspend fun archiveDescriptor(member: Long, id: String) = delivery.descriptor(member, id)
    suspend fun archiveDownload(member: Long, id: String) = delivery.download(member, id)
    private fun now() = Clock.System.now().toEpochMilliseconds()
    private fun missing(): Nothing = throw NotFoundException("STICKER_NOT_FOUND")
    private fun digest(bytes: ByteArray) = CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
    private fun authorDto(r: Row) = AuthorDto(r.long("id").toString(),r.string("name"),r.string("bio"),r.int("status"))
    private fun pageCheck(page: Int, size: Int) { StickerRules.check(page in 1..100000 && size in 1..100,"invalid_page") }
    private fun publicUrl(id: Long): String {
        check(Regex("https?://[^/]+(?:/.*)?").matches(publicBaseUrl)) { "Configure sticker.public_base_url as an absolute API base URL" }
        return "$publicBaseUrl/app/sticker/resource/get/$id"
    }
    suspend fun authors(): List<AuthorDto> = table.rows("SELECT * FROM sticker_authors ORDER BY id").map(::authorDto)
    suspend fun author(id: String): AuthorDto = authorDto(table.author(StickerRules.id(id)) ?: missing())
    suspend fun authorPage(page: Int, size: Int): PageResponse<AuthorDto> {
        pageCheck(page,size)
        val count=table.rows("SELECT COUNT(*) AS n FROM sticker_authors").single().long("n")
        val rows=table.rows("SELECT * FROM sticker_authors ORDER BY id LIMIT :size OFFSET :offset","size" to size,"offset" to (page-1)*size)
        return PageResponse(rows.map(::authorDto),count,page,size,((count+size-1)/size).toInt())
    }
    suspend fun saveAuthor(p: AuthorWrite, update: Boolean): AuthorDto = table.db.transaction {
        StickerRules.check(p.name.isNotBlank() && p.name.length<=128 && p.bio.length<=4000 && p.status in 0..1,"invalid_author")
        table.lock("authors")
        val id=if(update) {
            val id=StickerRules.id(p.id ?: ""); table.author(id) ?: missing()
            table.execute("UPDATE sticker_authors SET name=:name,bio=:bio,status=:status WHERE id=:id","name" to p.name,"bio" to p.bio,"status" to p.status,"id" to id); id
        } else {
            StickerRules.check(p.id==null,"unexpected_id")
            table.rows("INSERT INTO sticker_authors(name,bio,status) VALUES(:name,:bio,:status) RETURNING id","name" to p.name,"bio" to p.bio,"status" to p.status).single().long("id")
        }
        author(id.toString())
    }
    suspend fun deleteAuthor(value: String): Boolean = table.db.transaction {
        val id=StickerRules.id(value); table.lock("authors"); table.author(id) ?: missing()
        StickerRules.check(table.rows("SELECT id FROM sticker_packs WHERE author_id=:id UNION ALL SELECT id FROM sticker_resources WHERE author_id=:id LIMIT 1","id" to id).isEmpty(),"author_in_use")
        table.execute("DELETE FROM sticker_authors WHERE id=:id","id" to id)==1L
    }
    private suspend fun requireAuthor(id: Long) { StickerRules.check((table.author(id) ?: missing()).int("status")==1,"author_disabled") }
    private suspend fun packRow(id: String): Row = table.pack(StickerRules.id(id)) ?: missing()
    private suspend fun packDto(row: Row, admin: Boolean): PackDto {
        val entries=table.rows("SELECT r.*,e.key,e.emoji FROM sticker_entries e JOIN sticker_resources r ON r.id=e.resource_id WHERE e.pack_version_id=:id ORDER BY e.position","id" to row.long("id"))
        val cover=row.longOrNull("cover_id")
        return PackDto(row.long("id").toString(),row.string("pack_id"),Json.decodeFromString(row.string("name_json")),row.long("author_id").toString(),row.int("version"),row.int("publication_state"),entries.map { resourceDto(it,it.string("key"),it.string("emoji"),admin) },cover?.toString(),row.long("created_at"),cover?.let { if(admin) "/admin/sticker/resource/get/$it" else publicUrl(it) },row.int("default_install"),row.long("id").toString())
    }
    private suspend fun resourceDto(r: Row, key: String="", emoji: String="", admin: Boolean=false): StickerDto {
        val pag = table.rows("SELECT r.* FROM sticker_pag_links l JOIN sticker_resources r ON r.id=l.pag_resource_id WHERE l.fallback_resource_id=:id AND r.ready=1" + (if(admin) "" else " AND r.ever_published=1"),"id" to r.long("id")).firstOrNull()
        val descriptor = pag?.let {
            val metadata=Json.decodeFromString<PagMetadata>(it.string("pag_metadata_json"))
            PagDescriptor(it.long("id").toString(),if(admin) "/admin/sticker/resource/get/${it.long("id")}" else publicUrl(it.long("id")),it.string("digest"),it.long("size"),it.int("width"),it.int("height"),metadata.durationMs)
        }
        return StickerDto(r.long("id").toString(),key,emoji,r.int("width"),r.int("height"),r.string("mime_type"),r.long("size"),if(admin) "/admin/sticker/resource/get/${r.long("id")}" else publicUrl(r.long("id")),descriptor)
    }
    suspend fun pack(id: String) = packDto(packRow(id),true)
    suspend fun packs(): List<PackDto> = table.rows("SELECT * FROM sticker_packs ORDER BY id DESC").map { packDto(it,true) }
    suspend fun packPage(page: Int,size: Int): PageResponse<PackDto> {
        pageCheck(page,size)
        val count=table.rows("SELECT COUNT(*) AS n FROM sticker_packs").single().long("n")
        val rows=table.rows("SELECT * FROM sticker_packs ORDER BY id DESC LIMIT :size OFFSET :offset","size" to size,"offset" to (page-1)*size)
        return PageResponse(rows.map { packDto(it,true) },count,page,size,((count+size-1)/size).toInt())
    }
    suspend fun savePack(p: PackWrite,actor: Long,update: Boolean): PackDto = table.db.transaction {
        StickerRules.pack(p); table.lock("authors"); table.lock("pack:${p.packId}"); requireAuthor(StickerRules.id(p.authorId))
        val id=if(update) {
            val row=packRow(p.id ?: ""); StickerRules.draft(row.int("publication_state"))
            StickerRules.check(row.string("pack_id")==p.packId && row.int("version")==p.version && row.long("author_id")==StickerRules.id(p.authorId),"immutable_pack_identity")
            table.execute("UPDATE sticker_packs SET name_json=:name,default_install=:default WHERE id=:id","name" to Json.encodeToString(p.name),"default" to p.defaultInstall,"id" to row.long("id")); row.long("id")
        } else {
            StickerRules.check(p.id==null,"unexpected_id"); checkVersion(p); insertPack(p,actor,"manual","")
        }
        pack(id.toString())
    }
    private suspend fun checkVersion(p: PackWrite) {
        val max=table.rows("SELECT COALESCE(MAX(version),0) AS n FROM sticker_packs WHERE pack_id=:pack","pack" to p.packId).single().int("n")
        StickerRules.check(p.version>max,"version_conflict")
        val owner=table.rows("SELECT author_id FROM sticker_packs WHERE pack_id=:pack LIMIT 1","pack" to p.packId).firstOrNull()
        StickerRules.check(owner==null || owner.long("author_id")==StickerRules.id(p.authorId),"pack_author_conflict")
    }
    private suspend fun insertPack(p: PackWrite,actor: Long,source: String,hash: String): Long = table.rows("INSERT INTO sticker_packs(pack_id,name_json,author_id,version,input_digest,source,operator_id,created_at,default_install) VALUES(:pack,:name,:author,:version,:hash,:source,:actor,:now,:default) RETURNING id","pack" to p.packId,"name" to Json.encodeToString(p.name),"author" to StickerRules.id(p.authorId),"version" to p.version,"hash" to hash,"source" to source,"actor" to actor,"now" to now(),"default" to p.defaultInstall).single().long("id")
    suspend fun deletePack(id: String): Boolean = table.db.transaction {
        val initial=packRow(id); table.lock("pack:${initial.string("pack_id")}")
        val row=packRow(id); StickerRules.draft(row.int("publication_state"))
        table.execute("DELETE FROM sticker_pag_links WHERE fallback_resource_id IN (SELECT resource_id FROM sticker_entries WHERE pack_version_id=:id)","id" to row.long("id"))
        table.execute("DELETE FROM sticker_packs WHERE id=:id","id" to row.long("id"))==1L
    }
    suspend fun transition(id: String,target: Int): PackDto = table.db.transaction {
        if(target==StickerRules.PUBLISHED) table.lock("authors")
        val initial=packRow(id); table.lock("pack:${initial.string("pack_id")}")
        val row=packRow(id); StickerRules.transition(row.int("publication_state"),target)
        if(target==StickerRules.PUBLISHED) {
            requireAuthor(row.long("author_id"))
            val newer=table.rows("SELECT id FROM sticker_packs WHERE pack_id=:pack AND version>:version AND publication_state IN (2,3) LIMIT 1","pack" to row.string("pack_id"),"version" to row.int("version"))
            StickerRules.check(newer.isEmpty(),"stale_version")
            val resources=table.rows("SELECT r.* FROM sticker_resources r WHERE r.id=:cover OR r.id IN (SELECT resource_id FROM sticker_entries WHERE pack_version_id=:id) OR r.id IN (SELECT l.pag_resource_id FROM sticker_pag_links l JOIN sticker_entries e ON e.resource_id=l.fallback_resource_id WHERE e.pack_version_id=:id)","cover" to row.longOrNull("cover_id"),"id" to row.long("id"))
            StickerRules.check(row.longOrNull("cover_id")!=null && resources.isNotEmpty(),"empty_pack")
            for(r in resources) StickerRules.check(r.int("ready")==1 && storage.exists(r.string("object_key")),"missing_resource")
            delivery.freeze(row)
            table.execute("UPDATE sticker_resources SET ever_published=1 WHERE id=:cover OR id IN (SELECT resource_id FROM sticker_entries WHERE pack_version_id=:id) OR id IN (SELECT l.pag_resource_id FROM sticker_pag_links l JOIN sticker_entries e ON e.resource_id=l.fallback_resource_id WHERE e.pack_version_id=:id)","cover" to row.longOrNull("cover_id"),"id" to row.long("id"))
            table.execute("UPDATE sticker_packs SET publication_state=3 WHERE pack_id=:pack AND publication_state=2","pack" to row.string("pack_id"))
        }
        table.execute("UPDATE sticker_packs SET publication_state=:state WHERE id=:id","state" to target,"id" to row.long("id"))
        pack(id)
    }
    suspend fun importZip(bytes: ByteArray,authorId: String,actor: Long): PackDto {
        val bundle=StickerArchive.unpack(bytes); val m=bundle.manifest
        StickerRules.check(author(authorId).name==m.author,"manifest_author_mismatch")
        return receive(PackWrite(m.packId,m.name,authorId,m.version,defaultInstall=m.defaultInstall),actor,"zip",digest(bytes),m.cover,m.stickers,bundle.images)
    }
    suspend fun importZipV2(bytes: ByteArray,authorId: String,actor: Long): PackDto {
        val bundle=StickerArchive.unpackV2(bytes,pagInspector); val m=bundle.manifest
        StickerRules.check(author(authorId).name==m.author,"manifest_author_mismatch")
        return receive(PackWrite(m.packId,m.name,authorId,m.version,defaultInstall=m.defaultInstall),actor,"zip-v2",digest(bytes),m.cover,
            m.stickers.map { ManifestEntry(it.key,it.file,it.emoji,it.width,it.height) },bundle.images,bundle.pagLinks)
    }
    suspend fun make(bytes: ByteArray,p: PackWrite,key: String,actor: Long): PackDto {
        StickerRules.check(Regex("[A-Za-z0-9_-]{1,128}").matches(key),"invalid_key")
        val image=StickerArchive.image(bytes)
        val hash=digest(bytes+Json.encodeToString(p.copy(id=null)).encodeToByteArray()+key.encodeToByteArray())
        return receive(p,actor,"single",hash,"image",listOf(ManifestEntry(key,"image","",image.width,image.height)),mapOf("image" to image))
    }
    private suspend fun receive(p: PackWrite,actor: Long,source: String,hash: String,cover: String,entries: List<ManifestEntry>,images: Map<String,StickerArchive.Image>,pagLinks: Map<String,String> = emptyMap()): PackDto {
        StickerRules.pack(p)
        val retry = table.db.transaction {
            table.lock("authors"); table.lock("pack:${p.packId}")
            val old = validateReceive(p, hash)
            if(old != null && old.string("input_digest").isNotEmpty()) packDto(old, true) else null
        }
        if(retry != null) return retry
        // Reservations commit before writes. A crash leaves only unreferenced private objects, reclaimable by cleanup.
        val reservation=mutableMapOf<String,Long>()
        table.db.transaction {
            table.lock("authors"); requireAuthor(StickerRules.id(p.authorId))
            images.forEach { (name,img) ->
                val nonce=Random.nextBytes(24).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
                reservation[name]=table.rows("INSERT INTO sticker_resources(object_key,digest,mime_type,width,height,size,author_id,operator_id,created_at) VALUES(:path,:hash,:mime,:width,:height,:size,:author,:actor,:now) RETURNING id","path" to "platform/stickers/$nonce","hash" to digest(img.bytes),"mime" to img.mime,"width" to img.width,"height" to img.height,"size" to img.bytes.size.toLong(),"author" to StickerRules.id(p.authorId),"actor" to actor,"now" to now()).single().long("id")
                img.pag?.let { table.execute("UPDATE sticker_resources SET pag_profile=:profile,pag_metadata_json=:metadata WHERE id=:id",
                    "profile" to PagValidation.STICKER,"metadata" to Json.encodeToString(it),"id" to reservation.getValue(name)) }
            }
        }
        // Same lock as cleanup, including external writes. DB rollback does NOT roll back those writes.
        val outcome = table.db.transaction {
            table.lock("authors"); table.lock("uploads"); table.lock("pack:${p.packId}")
            // Validation happens again after reservation: another importer may have won the race.
            val old = try { validateReceive(p, hash) } catch(error: HttpException) {
                deleteUnwrittenReservations(reservation.values)
                return@transaction Result.failure<PackDto>(error)
            }
            if(old!=null && old.string("input_digest").isNotEmpty()) {
                deleteUnwrittenReservations(reservation.values)
                return@transaction Result.success(packDto(old,true))
            }
            val packId=if(old==null) { insertPack(p,actor,source,hash) } else {
                table.execute("UPDATE sticker_packs SET input_digest=:hash,source=:source,operator_id=:actor WHERE id=:id","hash" to hash,"source" to source,"actor" to actor,"id" to old.long("id")); old.long("id")
            }
            for((name,id) in reservation) {
                val r=table.resource(id) ?: missing()
                storage.write(r.string("object_key"),images.getValue(name).bytes, neton.storage.WriteOptions(overwrite=false))
                table.execute("UPDATE sticker_resources SET ready=1 WHERE id=:id","id" to id)
            }
            entries.forEachIndexed { index,e -> table.execute("INSERT INTO sticker_entries(pack_version_id,resource_id,key,emoji,position) VALUES(:pack,:resource,:key,:emoji,:position)","pack" to packId,"resource" to reservation.getValue(e.file),"key" to e.key,"emoji" to e.emoji,"position" to index) }
            pagLinks.forEach { (fallback,pag) -> table.execute("INSERT INTO sticker_pag_links(fallback_resource_id,pag_resource_id) VALUES(:fallback,:pag)",
                "fallback" to reservation.getValue(fallback),"pag" to reservation.getValue(pag)) }
            table.execute("UPDATE sticker_packs SET cover_id=:cover WHERE id=:id","cover" to reservation.getValue(cover),"id" to packId)
            Result.success(pack(packId.toString()))
        }
        return outcome.getOrThrow()
    }
    private suspend fun validateReceive(p: PackWrite, hash: String): Row? {
        requireAuthor(StickerRules.id(p.authorId))
        val old=table.rows("SELECT * FROM sticker_packs WHERE pack_id=:pack AND version=:version","pack" to p.packId,"version" to p.version).firstOrNull()
        if(old == null) { checkVersion(p); return null }
        if(old.string("input_digest").isNotEmpty()) {
            StickerRules.check(old.string("input_digest")==hash && old.long("author_id")==StickerRules.id(p.authorId),"version_conflict")
        } else {
            StickerRules.draft(old.int("publication_state"))
            StickerRules.check(old.long("author_id")==StickerRules.id(p.authorId) && Json.decodeFromString<Map<String,String>>(old.string("name_json"))==p.name && old.int("default_install")==p.defaultInstall,"draft_metadata_mismatch")
        }
        return old
    }
    private suspend fun deleteUnwrittenReservations(ids: Collection<Long>) {
        for(id in ids) table.execute("DELETE FROM sticker_resources WHERE id=:id AND ready=0 AND ever_published=0 AND NOT EXISTS(SELECT 1 FROM sticker_entries WHERE resource_id=:id) AND NOT EXISTS(SELECT 1 FROM sticker_packs WHERE cover_id=:id)","id" to id)
    }
    suspend fun cleanup(): Int = cleanupResources() + delivery.cleanup()
    private suspend fun cleanupResources(): Int = table.db.transaction {
        table.lock("uploads")
        val unused=table.rows("SELECT r.* FROM sticker_resources r WHERE r.ever_published=0 AND r.created_at<:cutoff AND NOT EXISTS(SELECT 1 FROM sticker_entries e WHERE e.resource_id=r.id) AND NOT EXISTS(SELECT 1 FROM sticker_packs p WHERE p.cover_id=r.id) AND NOT EXISTS(SELECT 1 FROM sticker_pag_links l WHERE l.fallback_resource_id=r.id OR l.pag_resource_id=r.id) ORDER BY r.id LIMIT 100","cutoff" to now()-86400000)
        for(r in unused) {
            if(storage.exists(r.string("object_key"))) storage.delete(r.string("object_key"))
            table.execute("DELETE FROM sticker_resources WHERE id=:id","id" to r.long("id"))
        }
        unused.size
    }
    suspend fun resolve(id: String): StickerDto {
        val r=table.resource(StickerRules.id(id)) ?: missing()
        if(r.int("ever_published")!=1 || r.int("ready")!=1) missing()
        if(r.stringOrNull("pag_profile")!=null) missing()
        return resourceDto(r)
    }
    suspend fun bytes(id: String,admin: Boolean): Pair<ByteArray,String> {
        val r=table.resource(StickerRules.id(id)) ?: missing()
        if(r.int("ready")!=1 || (!admin && r.int("ever_published")!=1)) missing()
        return storage.read(r.string("object_key")) to r.string("mime_type")
    }
    suspend fun store(): List<PackDto> = table.rows("SELECT * FROM sticker_packs WHERE publication_state=2 ORDER BY id DESC").map { packDto(it,false) }
    // Membership in an immutable published version excludes cover-only and raw PAG resources.
    private val favoriteEntries = """
        FROM sticker_entries e JOIN sticker_packs p ON p.id=e.pack_version_id
        JOIN sticker_resources r ON r.id=e.resource_id
        WHERE p.publication_state IN (2,3) AND r.ever_published=1 AND r.ready=1
          AND r.pag_profile IS NULL AND r.mime_type IN ('image/png','image/webp')
          AND r.width>0 AND r.height>0 AND r.size>0
    """.trimIndent()

    suspend fun favorites(member: Long): List<StickerDto> = table.rows("""
        SELECT r.*,entry.key,entry.emoji FROM sticker_favorites f
        JOIN sticker_resources r ON r.id=f.resource_id
        JOIN LATERAL (SELECT e.key,e.emoji $favoriteEntries AND r.id=f.resource_id
            ORDER BY p.version DESC,p.id DESC,e.position LIMIT 1) entry ON TRUE
        WHERE f.member_id=:member ORDER BY f.created_at DESC,f.id DESC LIMIT :limit
    """.trimIndent(), "member" to member, "limit" to StickerSearchRules.MAX_FAVORITES)
        .map { resourceDto(it,it.string("key"),it.string("emoji")) }

    suspend fun addFavorite(member: Long, id: String): Boolean = table.db.transaction {
        val resource = StickerRules.id(id)
        table.lock("member:$member")
        if (table.rows("SELECT r.id $favoriteEntries AND r.id=:id LIMIT 1", "id" to resource).isEmpty()) missing()
        val existing = table.rows("SELECT id FROM sticker_favorites WHERE member_id=:member AND resource_id=:id",
            "member" to member, "id" to resource).isNotEmpty()
        if (!existing) {
            val count = table.rows("SELECT COUNT(*) AS n FROM sticker_favorites WHERE member_id=:member", "member" to member).single().long("n")
            StickerRules.check(count < StickerSearchRules.MAX_FAVORITES, "favorite_limit")
            table.execute("INSERT INTO sticker_favorites(member_id,resource_id,created_at) VALUES(:member,:id,:now) ON CONFLICT(member_id,resource_id) DO NOTHING",
                "member" to member, "id" to resource, "now" to now())
        }
        true
    }

    suspend fun removeFavorite(member: Long, id: String): Boolean = table.db.transaction {
        val resource = StickerRules.id(id)
        table.lock("member:$member")
        table.execute("DELETE FROM sticker_favorites WHERE member_id=:member AND resource_id=:id", "member" to member, "id" to resource)
        true
    }

    suspend fun search(member: Long, query: String): StickerSearchResult {
        val pattern = StickerSearchRules.pattern(query) ?: return StickerSearchResult(emptyList(), emptyList())
        val packs = table.rows("""
            SELECT p.* FROM sticker_packs p WHERE p.publication_state=2
            AND (p.pack_id ILIKE :pattern ESCAPE '!' OR EXISTS (
                SELECT 1 FROM jsonb_each_text(p.name_json::jsonb) n WHERE n.value ILIKE :pattern ESCAPE '!'))
            ORDER BY p.default_install DESC,EXISTS (SELECT 1 FROM sticker_installs i
                WHERE i.member_id=:member AND i.pack_id=p.pack_id AND i.status=1) DESC,p.id DESC LIMIT :limit
        """.trimIndent(), "member" to member, "pattern" to pattern, "limit" to StickerSearchRules.MAX_PACKS).map { packDto(it,false) }
        val stickers = table.rows("""
            SELECT DISTINCT ON (r.id) r.*,e.key,e.emoji $favoriteEntries
            AND (p.publication_state=2 OR EXISTS (SELECT 1 FROM sticker_favorites f WHERE f.member_id=:member AND f.resource_id=r.id))
            AND (e.key ILIKE :pattern ESCAPE '!' OR e.emoji ILIKE :pattern ESCAPE '!'
                OR p.pack_id ILIKE :pattern ESCAPE '!' OR EXISTS (
                    SELECT 1 FROM jsonb_each_text(p.name_json::jsonb) n WHERE n.value ILIKE :pattern ESCAPE '!'))
            ORDER BY r.id,p.version DESC,p.id DESC,e.position LIMIT :limit
        """.trimIndent(), "member" to member, "pattern" to pattern, "limit" to StickerSearchRules.MAX_STICKERS)
            .map { resourceDto(it,it.string("key"),it.string("emoji")) }
        return StickerSearchResult(packs, stickers)
    }
    // Call only under the account lock, in the same transaction as every install/order decision.
    // A row with status=0 is an explicit opt-out and must never be overwritten by defaults.
    private suspend fun initializeDefaults(member: Long) {
        table.execute("INSERT INTO sticker_installs(member_id,pack_id,position,installed_at,status) SELECT :member,p.pack_id,(SELECT COALESCE(MAX(position),-1) FROM sticker_installs WHERE member_id=:member AND status=1)+ROW_NUMBER() OVER(ORDER BY p.id),:now,1 FROM sticker_packs p WHERE p.publication_state=2 AND p.default_install=1 AND NOT EXISTS(SELECT 1 FROM sticker_installs i WHERE i.member_id=:member AND i.pack_id=p.pack_id) ON CONFLICT(member_id,pack_id) DO NOTHING","member" to member,"now" to now())
    }
    suspend fun installed(member: Long): List<PackDto> = table.db.transaction {
        table.lock("member:$member"); initializeDefaults(member)
        table.rows("SELECT p.* FROM sticker_installs i JOIN sticker_packs p ON p.pack_id=i.pack_id WHERE i.member_id=:member AND i.status=1 AND p.version=(SELECT MAX(p2.version) FROM sticker_packs p2 WHERE p2.pack_id=i.pack_id AND p2.publication_state IN (2,3)) ORDER BY i.position,i.id","member" to member).map { packDto(it,false) }
    }
    suspend fun install(member: Long,key: String,versionRowId: String? = null): Boolean = table.db.transaction {
        table.lock("member:$member"); table.lock("pack:$key")
        val current = table.rows("SELECT id FROM sticker_packs WHERE pack_id=:pack AND publication_state=2","pack" to key).firstOrNull()
        if (versionRowId != null) StickerRules.check(current?.long("id") == StickerRules.id(versionRowId), "version_changed")
        StickerRules.check(current != null,"pack_unavailable")
        initializeDefaults(member)
        table.execute("INSERT INTO sticker_installs(member_id,pack_id,position,installed_at,status) SELECT :member,:pack,COALESCE(MAX(position),-1)+1,:now,1 FROM sticker_installs WHERE member_id=:member AND status=1 ON CONFLICT(member_id,pack_id) DO UPDATE SET status=1,position=EXCLUDED.position,installed_at=EXCLUDED.installed_at WHERE sticker_installs.status=0","member" to member,"pack" to key,"now" to now()); true
    }
    suspend fun uninstall(member: Long,key: String): Boolean = table.db.transaction {
        table.lock("member:$member")
        StickerRules.check(Regex("[A-Za-z0-9_-]{1,128}").matches(key),"invalid_pack_id")
        initializeDefaults(member)
        table.execute("INSERT INTO sticker_installs(member_id,pack_id,position,installed_at,status) VALUES(:member,:pack,0,:now,0) ON CONFLICT(member_id,pack_id) DO UPDATE SET status=0","member" to member,"pack" to key,"now" to now()); true
    }
    suspend fun reorder(member: Long,keys: List<String>): Boolean = table.db.transaction {
        table.lock("member:$member")
        initializeDefaults(member)
        val current=table.rows("SELECT pack_id FROM sticker_installs WHERE member_id=:member AND status=1","member" to member).map { it.string("pack_id") }.toSet()
        StickerRules.check(keys.size==keys.toSet().size && keys.toSet()==current,"invalid_install_order")
        keys.forEachIndexed { index,key -> table.execute("UPDATE sticker_installs SET position=:position WHERE member_id=:member AND pack_id=:pack AND status=1","position" to index,"member" to member,"pack" to key) }; true
    }
}
