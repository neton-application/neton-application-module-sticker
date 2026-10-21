package init
import neton.core.annotations.Module
@Module(dependsOn = ["system", "infra", "account"], migrations = true)
object StickerModule
