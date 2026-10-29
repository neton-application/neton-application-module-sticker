// Test fixture opens the database directly; that entry point is an internal Neton API.
@file:OptIn(neton.core.annotations.InternalNetonApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.coroutines.*
import kotlinx.cinterop.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import neton.core.component.NetonContext
import neton.core.config.getEnv
import neton.core.http.*
import neton.core.http.adapter.HttpServerConfig
import neton.core.interfaces.*
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.config.*
import neton.http.engine.default.DefaultHttpAdapter
import neton.http.engine.default.DefaultHttpOptions
import neton.module.sticker.generated.StickerRouteInitializer
import neton.security.AuthenticationContextImpl
import neton.security.DefaultGuardImpl
import neton.security.identity.UserId
import neton.security.jwt.JwtAuthenticator
import neton.security.jwt.VerifiedJwtToken
import neton.storage.*
import security.*
import sticker.logic.StickerLogic
import sticker.table.StickerTable
import kotlin.test.Test

/** Opt-in socket host: production routes/storage/SQL/security pipeline with explicit test-only identities.
 * This does not test the system module's login, MFA, role persistence or revocation implementation.
 */
class StickerHttpServerTest {
    @Test fun serveDisposableLoopbackHost() = runBlocking<Unit> {
        if(getEnv("STICKER_HTTP_TEST_HOST")!="1") return@runBlocking
        val uri=getEnv("STICKER_TEST_DATABASE_URI") ?: error("STICKER_TEST_DATABASE_URI required")
        val config=DatabaseConfig(DatabaseDriver.POSTGRESQL,uri)
        val info=config.parseUri() as PostgresUriInfo
        require(info.host=="127.0.0.1" && info.port==55473 && info.database=="sticker_test")
        val db=SqlxDatabase.initialize(config)
        val ctx=NetonContext(emptyArray())
        val routes=mutableListOf<RouteDefinition>()
        ctx.bind(RequestEngine::class,object: RequestEngine {
            override fun registerRoute(route: RouteDefinition) { routes+=route }
            override fun getRoutes() = routes.toList()
        })
        StorageComponent.init(ctx,StorageConfig().apply {
            source("default") { basePath="/tmp/sticker-integration-storage" }
            source("sticker-private") { basePath="/tmp/sticker-integration-storage" }
        })
        ctx.bind(StickerLogic::class,StickerLogic(StickerTable(db),ctx.get(StorageManager::class),"http://127.0.0.1:55474"))
        StickerRouteInitializer.initialize(ctx)
        val jwt=JwtAuthenticator(secretKey="sticker-loopback-test-only-secret-0123456789abcdef")
        val registry=TokenRealmRegistry()
        listOf("admin" to "admin","account" to "app").forEach { (realm,group) ->
            registry.register(object: TokenRealm {
                override val name=realm
                override val routeGroups=setOf(group)
                override val acceptsExternalTokens=false
                override suspend fun resolve(token: VerifiedJwtToken): neton.core.interfaces.Identity? {
                    if(token.claimString("type")!="access") return null
                    val id=token.identity.userId
                    val permissions=when(id.value.toLong()) {
                        9001L -> setOf("sticker:*:*")
                        9002L -> setOf("sticker:pack:query","sticker:author:query")
                        9003L -> emptySet()
                        else -> return null
                    }
                    return RealmIdentity(id,setOf(realm),permissions,realm,null)
                }
            })
        }
        val authenticator=RealmAuthenticator(jwt,null,registry,"Authorization","Bearer ","neton_session")
        val guard=DefaultGuardImpl()
        ctx.bind(SecurityConfiguration::class,SecurityConfiguration(true,1,1,AuthenticationContextImpl(listOf(authenticator),listOf(guard)),authenticator,guard,permissionEvaluator=WildcardPermissionEvaluator()))
        ctx.bind(RouteGroupSecurityConfigs::class,RouteGroupSecurityConfigs(mapOf("admin" to RouteGroupSecurityConfig(true,emptySet()),"app" to RouteGroupSecurityConfig(true,emptySet()))))
        // A real authenticated permission response for the frontend test harness, not a fetch stub.
        routes+=RouteDefinition("/system/auth/get-permission-info",HttpMethod.GET,object: RouteHandler {
            override suspend fun invoke(context: HttpContext,args: HandlerArgs): Any {
                val identity=context.getAttribute(SecurityAttributes.IDENTITY) as Identity
                // Parent acceptance setup applied the real V002 seed in this isolated schema.
                val rows=db.fetchAll("SELECT * FROM migration_check.system_menus WHERE path='/sticker' OR permission LIKE 'sticker:%' ORDER BY sort,id")
                    .filter { it.stringOrNull("permission").isNullOrBlank() || "sticker:*:*" in identity.permissions || it.string("permission") in identity.permissions }
                fun menu(row: neton.database.api.Row): controller.admin.menu.dto.MenuVO = controller.admin.menu.dto.MenuVO(
                    id=row.long("id"),name=row.string("name"),permission=row.stringOrNull("permission"),type=row.int("type"),parentId=row.long("parent_id"),
                    path=row.stringOrNull("path"),component=row.stringOrNull("component"),icon=row.stringOrNull("icon"),sort=row.int("sort"),status=row.int("status"),visible=true,keepAlive=true,
                    children=rows.filter { it.long("parent_id")==row.long("id") }.map { menu(it) },
                )
                val menus=rows.filter { it.long("parent_id")==0L }.map { menu(it) }
                val result=controller.admin.auth.dto.PermissionInfoVO(controller.admin.auth.dto.UserInfoVO(identity.id,"sticker-test","Sticker test"),identity.roles.toList(),identity.permissions.toList(),menus)
                return JsonContent(Json.encodeToString(result))
            }
        },routeGroup="admin",requireAuth=true)
        fun token(id: UInt,realm: String,type: String="access")=jwt.createToken(UserId(id.toULong()),expiresInSeconds=7200,extraClaims=mapOf("realm" to realm,"type" to type))
        val credentials = Json.encodeToString(mapOf(
            "ADMIN" to token(9001u,"admin"), "READONLY" to token(9002u,"admin"),
            "ACCOUNT" to token(9003u,"account"), "REFRESH" to token(9001u,"admin","refresh"),
        ))
        val credentialPath = writePrivateCredentials(credentials)
        println("STICKER_HTTP_CREDENTIALS=$credentialPath")
        platform.posix.signal(platform.posix.SIGPIPE,platform.posix.SIG_IGN)
        val adapter=DefaultHttpAdapter(HttpServerConfig(port=55474,host="127.0.0.1"),DefaultHttpOptions(reactors=1,maxRequestBodyBytes=34L*1024*1024,maxBufferedRequestBytes=68L*1024*1024))
        val ready=CompletableDeferred<Unit>()
        val server=launch(Dispatchers.Default) { adapter.start(ctx) { ready.complete(Unit) } }
        server.invokeOnCompletion { e -> if(e!=null) ready.completeExceptionally(e) }
        try {
            withTimeout(15000) { ready.await() }
            println("STICKER_HTTP_READY=http://127.0.0.1:55474")
            delay(30*60*1000L)
        } finally { adapter.stop(); server.join(); SqlxDatabase.close(); platform.posix.unlink(credentialPath) }
    }

    private fun writePrivateCredentials(json: String): String {
        val template = "/tmp/sticker-http-credentials-XXXXXX\u0000".encodeToByteArray()
        val fd = template.usePinned { platform.posix.mkstemp(it.addressOf(0)) }
        check(fd >= 0) { "Cannot create private fixture credential file" }
        try {
            check(platform.posix.fchmod(fd, 384u.convert()) == 0)
            val bytes = json.encodeToByteArray()
            bytes.usePinned { pinned ->
                var offset = 0
                while(offset < bytes.size) {
                    val n = platform.posix.write(fd, pinned.addressOf(offset), (bytes.size-offset).convert()).toInt()
                    check(n > 0) { "Cannot write fixture credentials" }
                    offset += n
                }
            }
        } finally { platform.posix.close(fd) }
        return template.decodeToString().trimEnd('\u0000')
    }
}
