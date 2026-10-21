package controller.admin.sticker
import neton.core.annotations.*
import neton.core.http.*
import sticker.logic.StickerLogic

@Controller("/sticker/resource")
class StickerResourceController(private val logic: StickerLogic) {
    @Get("/get/{id}") @Permission("sticker:pack:query")
    suspend fun get(@PathVariable id: String,response: HttpResponse) {
        val (data,mime)=logic.bytes(id,true)
        response.header("Cache-Control","private, no-store")
        response.header("X-Content-Type-Options","nosniff")
        response.header("Content-Security-Policy","default-src 'none'; sandbox")
        response.bytes(data=data,contentType=mime)
    }
    @Post("/cleanup") @Permission("sticker:pack:delete")
    suspend fun cleanup() = logic.cleanup()
}
