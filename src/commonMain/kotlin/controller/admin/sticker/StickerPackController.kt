package controller.admin.sticker
import kotlinx.serialization.json.Json
import neton.core.annotations.*
import neton.core.http.*
import neton.core.interfaces.Identity
import sticker.dto.*
import sticker.logic.*

@Controller("/sticker/pack")
class StickerPackController(private val logic: StickerLogic) {
    @Get("/page") @Permission("sticker:pack:query")
    suspend fun page(@Query pageNo: Int = 1,@Query pageSize: Int = 20) = logic.packPage(pageNo,pageSize)
    @Get("/list") @Permission("sticker:pack:query")
    suspend fun list() = logic.packs()
    @Get("/get/{id}") @Permission("sticker:pack:query")
    suspend fun get(@PathVariable id: String) = logic.pack(id)
    @Post("/create") @Permission("sticker:pack:create")
    suspend fun create(@Body body: PackWrite,identity: Identity) = logic.savePack(body,StickerRules.id(identity.id),false)
    @Put("/update") @Permission("sticker:pack:update")
    suspend fun update(@Body body: PackWrite,identity: Identity) = logic.savePack(body,StickerRules.id(identity.id),true)
    @Delete("/delete/{id}") @Permission("sticker:pack:delete")
    suspend fun delete(@PathVariable id: String) = logic.deletePack(id)
    @Post("/publish") @Permission("sticker:pack:publish")
    suspend fun publish(@Body body: IdRequest) = logic.transition(body.id,StickerRules.PUBLISHED)
    @Post("/offline") @Permission("sticker:pack:offline")
    suspend fun offline(@Body body: IdRequest) = logic.transition(body.id,StickerRules.OFFLINE)
    @Post("/import") @Permission("sticker:pack:import")
    suspend fun importZip(request: HttpRequest,identity: Identity): PackDto {
        val data=upload(request,StickerRules.MAX_PACKAGE)
        val author=request.form()["authorId"] ?: throw BadRequestException("STICKER_AUTHOR_REQUIRED")
        return logic.importZip(data,author,StickerRules.id(identity.id))
    }
    @Post("/make") @Permission("sticker:pack:make")
    suspend fun make(request: HttpRequest,identity: Identity): PackDto {
        val data=upload(request,StickerRules.MAX_IMAGE)
        val f=request.form()
        val names=try { Json.decodeFromString<Map<String,String>>(f["name"] ?: "") } catch (_: kotlinx.serialization.SerializationException) { throw BadRequestException("STICKER_INVALID_NAME") }
        val defaultInstall=f["defaultInstall"]?.let { it.toIntOrNull() ?: throw BadRequestException("STICKER_INVALID_DEFAULT_INSTALL") } ?: 0
        val p=PackWrite(f["packId"] ?: "",names,f["authorId"] ?: "",f["version"]?.toIntOrNull() ?: 0,defaultInstall=defaultInstall)
        return logic.make(data,p,f["key"] ?: "",StickerRules.id(identity.id))
    }
    @Post("/import-v2") @Permission("sticker:pack:import")
    suspend fun importV2(request: HttpRequest,identity: Identity): PackDto {
        val data=upload(request,StickerRules.MAX_PACKAGE)
        val author=request.form()["authorId"] ?: throw BadRequestException("STICKER_AUTHOR_REQUIRED")
        return logic.importZipV2(data,author,StickerRules.id(identity.id))
    }
    private suspend fun upload(request: HttpRequest,limit: Int): ByteArray {
        StickerRules.check(request.isMultipart(),"multipart_required")
        val file=request.uploadFiles().first("file") ?: throw BadRequestException("STICKER_FILE_REQUIRED")
        StickerRules.check(file.size in 1..limit.toLong(),"upload_size")
        val bytes=file.bytes(); StickerRules.check(bytes.size in 1..limit,"upload_size"); return bytes
    }
}
