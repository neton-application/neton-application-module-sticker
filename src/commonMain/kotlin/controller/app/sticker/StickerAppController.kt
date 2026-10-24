package controller.app.sticker
import neton.core.annotations.*
import neton.core.http.*
import neton.core.interfaces.Identity
import sticker.dto.*
import sticker.logic.*

@Controller("/sticker")
class StickerAppController(private val logic: StickerLogic) {
    @Get("/store") @RequireAuth
    suspend fun store() = logic.store()
    @Get("/installed")
    suspend fun installed(identity: Identity) = logic.installed(StickerRules.id(identity.id))
    @Post("/install")
    suspend fun install(@Body body: InstallRequest,identity: Identity) = logic.install(StickerRules.id(identity.id),body.packId,body.versionRowId)
    @Post("/uninstall")
    suspend fun uninstall(@Body body: InstallRequest,identity: Identity) = logic.uninstall(StickerRules.id(identity.id),body.packId)
    @Post("/reorder")
    suspend fun reorder(@Body body: ReorderRequest,identity: Identity) = logic.reorder(StickerRules.id(identity.id),body.packIds)
    @Get("/resolve/{id}") @RequireAuth
    suspend fun resolve(@PathVariable id: String) = logic.resolve(id)
    @Get("/archive/{versionRowId}/manifest") @RequireAuth
    suspend fun archiveManifest(@PathVariable versionRowId: String, identity: Identity, response: HttpResponse): ArchiveDescriptor {
        response.header("Cache-Control", "private, no-store")
        return logic.archiveDescriptor(StickerRules.id(identity.id), versionRowId)
    }
    @Get("/archive/{versionRowId}/download") @RequireAuth
    suspend fun archiveDownload(@PathVariable versionRowId: String, identity: Identity, response: HttpResponse) {
        val (descriptor, data) = logic.archiveDownload(StickerRules.id(identity.id), versionRowId)
        response.header("Cache-Control", "private, no-store")
        response.header("ETag", "\"${descriptor.sha256}\"")
        response.header("Content-Length", descriptor.size.toString())
        response.header("Content-Disposition", "attachment; filename=\"sticker-${descriptor.versionRowId}.zip\"")
        response.header("X-Content-Type-Options", "nosniff")
        response.bytes(data = data, contentType = "application/zip")
    }
    @Get("/resource/get/{id}") @AllowAnonymous
    suspend fun resource(@PathVariable id: String,response: HttpResponse) {
        val (data,mime)=logic.bytes(id,false)
        response.header("Cache-Control","public, max-age=31536000, immutable")
        response.header("X-Content-Type-Options","nosniff")
        response.header("Content-Security-Policy","default-src 'none'; sandbox")
        response.bytes(data=data,contentType=mime)
    }
}
