package controller.admin.sticker
import neton.core.annotations.*
import sticker.dto.AuthorWrite
import sticker.logic.StickerLogic

@Controller("/sticker/author")
class StickerAuthorController(private val logic: StickerLogic) {
    @Get("/page") @Permission("sticker:author:query")
    suspend fun page(@Query pageNo: Int = 1,@Query pageSize: Int = 20) = logic.authorPage(pageNo,pageSize)
    @Get("/list") @Permission("sticker:author:query")
    suspend fun list() = logic.authors()
    @Get("/simple-list") @Permission("sticker:author:query")
    suspend fun simpleList() = logic.authors()
    @Get("/get/{id}") @Permission("sticker:author:query")
    suspend fun get(@PathVariable id: String) = logic.author(id)
    @Post("/create") @Permission("sticker:author:create")
    suspend fun create(@Body body: AuthorWrite) = logic.saveAuthor(body,false)
    @Put("/update") @Permission("sticker:author:update")
    suspend fun update(@Body body: AuthorWrite) = logic.saveAuthor(body,true)
    @Delete("/delete/{id}") @Permission("sticker:author:delete")
    suspend fun delete(@PathVariable id: String) = logic.deleteAuthor(id)
}
