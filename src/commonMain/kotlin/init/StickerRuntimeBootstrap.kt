package init
import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.database.api.DbContext
import neton.storage.StorageManager
import sticker.logic.StickerLogic
import sticker.logic.configuredPagInspector
import sticker.table.StickerTable

object StickerRuntimeBootstrap {
    fun initialize(ctx: NetonContext) {
        val config = ConfigLoader.loadModuleConfig(moduleName = "sticker")
        val base = ConfigLoader.getString(config, "public_base_url")?.trim()?.trimEnd('/') ?: ""
        require(Regex("https?://[A-Za-z0-9.-]+(?::[0-9]+)?(?:/[A-Za-z0-9._~%/-]*)?").matches(base)) {
            "sticker.public_base_url must be an absolute HTTP(S) API base URL without query or fragment"
        }
        val storage = ctx.get(StorageManager::class)
        require(storage.get("sticker-private").baseUrl.isBlank()) { "sticker-private must not expose a public baseUrl" }
        val inspector = configuredPagInspector(ConfigLoader.getString(config,"pag_inspector_executable"))
        ctx.bindIfAbsent(StickerLogic::class, StickerLogic(StickerTable(ctx.get(DbContext::class)), storage, base, inspector))
    }
}
