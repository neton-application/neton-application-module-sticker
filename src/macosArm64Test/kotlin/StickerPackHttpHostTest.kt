// Test fixture opens the database directly; that entry point is an internal Neton API.
@file:OptIn(neton.core.annotations.InternalNetonApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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
import neton.security.*
import neton.security.identity.UserId
import neton.security.jwt.*
import neton.storage.*
import platform.posix.*
import security.*
import sticker.logic.*
import sticker.table.StickerTable
import kotlin.random.Random
import kotlin.test.Test

/** Actual production routes/Logic/PG/storage. Explicit test realms, NOT production login acceptance. */
class StickerPackHttpHostTest {
    @Test fun serveDisposableStickerPacks() = runBlocking<Unit> {
        if (getEnv("STICKER_PACK_HTTP") != "1") return@runBlocking
        val uri = getEnv("STICKER_PACK_DATABASE_URI") ?: error("STICKER_PACK_DATABASE_URI required")
        val config = DatabaseConfig(DatabaseDriver.POSTGRESQL, uri)
        val info = config.parseUri() as PostgresUriInfo
        require(info.host == "127.0.0.1" && info.port == 55476 && Regex("sticker_packs_[a-z0-9_]+").matches(info.database))
        val root = getEnv("STICKER_PACK_ROOT") ?: error("STICKER_PACK_ROOT required")
        val projectRoot = "/Users/zoujiaqing/projects/privchat/.artifacts/feature-closure/2026-10-25/sticker-backend"
        require((root.startsWith("/tmp/sticker-packs.") || root == projectRoot || root.startsWith("$projectRoot/")) && !root.contains(".."))
        val port = getEnv("STICKER_PACK_PORT")?.toIntOrNull() ?: 55479
        require(port in 55479..55489)
        val origin = "http://127.0.0.1:$port"
        val db = SqlxDatabase.initialize(config)
        // Migration tooling must have applied V001/V003/V004 before this host starts; no DDL here.
        db.fetchAll("SELECT default_install FROM sticker_packs LIMIT 1")
        db.fetchAll("SELECT status FROM sticker_installs LIMIT 1")
        val ctx = NetonContext(emptyArray())
        val routes = mutableListOf<RouteDefinition>()
        ctx.bind(RequestEngine::class, object : RequestEngine {
            override fun registerRoute(route: RouteDefinition) { routes += route }
            override fun getRoutes() = routes.toList()
        })
        StorageComponent.init(ctx, StorageConfig().apply {
            source("default") { basePath = "$root/storage" }
            source("sticker-private") { basePath = "$root/storage" }
        })
        ctx.bind(StickerLogic::class, StickerLogic(StickerTable(db), ctx.get(StorageManager::class), origin,
            configuredPagInspector(getEnv("STICKER_PACK_INSPECTOR"))))
        StickerRouteInitializer.initialize(ctx)
        val secret = Random.nextBytes(48).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val jwt = JwtAuthenticator(secretKey = secret)
        val registry = TokenRealmRegistry()
        // Optional real IM fixture identities let the mobile host keep one account identity end-to-end.
        val additionalAccounts = getEnv("STICKER_PACK_ACCOUNT_IDS")?.split(',')?.map { raw ->
            requireNotNull(raw.toLongOrNull()).also { require(it > 0) }
        }.orEmpty()
        require(additionalAccounts.size <= 32 && additionalAccounts.distinct().size == additionalAccounts.size)
        val accountIds = (9101L..9132L).toSet() + setOf(9191L, 9192L) + additionalAccounts
        for ((realm, group) in listOf("admin" to "admin", "account" to "app")) {
            registry.register(object : TokenRealm {
                override val name = realm
                override val routeGroups = setOf(group)
                override val acceptsExternalTokens = false
                override suspend fun resolve(token: VerifiedJwtToken): Identity? {
                    if (token.claimString("type") != "access") return null
                    val id = token.identity.userId
                    val permissions = if (realm == "admin") when (id.value.toLong()) {
                        9001L -> setOf("sticker:*:*")
                        9002L -> setOf("sticker:pack:query", "sticker:author:query")
                        else -> return null
                    } else {
                        if (id.value.toLong() !in accountIds) return null
                        emptySet()
                    }
                    return RealmIdentity(id, setOf(realm), permissions, realm, null)
                }
            })
        }
        val auth = RealmAuthenticator(jwt, null, registry, "Authorization", "Bearer ", "neton_session")
        val guard = DefaultGuardImpl()
        ctx.bind(SecurityConfiguration::class, SecurityConfiguration(true, 1, 1,
            AuthenticationContextImpl(listOf(auth), listOf(guard)), auth, guard,
            permissionEvaluator = WildcardPermissionEvaluator()))
        ctx.bind(RouteGroupSecurityConfigs::class, RouteGroupSecurityConfigs(mapOf(
            "admin" to RouteGroupSecurityConfig(true, emptySet()), "app" to RouteGroupSecurityConfig(true, emptySet()))))
        // Frontend acceptance uses the shared authenticated permission-info response with actual V002 rows.
        routes += RouteDefinition("/system/auth/get-permission-info", HttpMethod.GET, object : RouteHandler {
            override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any {
                val identity = context.getAttribute(SecurityAttributes.IDENTITY) as Identity
                val rows = db.fetchAll("SELECT * FROM fixture_admin.system_menus ORDER BY sort,id").filter {
                    it.stringOrNull("permission").isNullOrBlank() || "sticker:*:*" in identity.permissions || it.string("permission") in identity.permissions
                }
                fun menu(row: neton.database.api.Row): controller.admin.menu.dto.MenuVO = controller.admin.menu.dto.MenuVO(
                    id = row.long("id"), name = row.string("name"), permission = row.stringOrNull("permission"), type = row.int("type"), parentId = row.long("parent_id"),
                    path = row.stringOrNull("path"), component = row.stringOrNull("component"), icon = row.stringOrNull("icon"), sort = row.int("sort"), status = row.int("status"), visible = true, keepAlive = true,
                    children = rows.filter { it.long("parent_id") == row.long("id") }.map { menu(it) })
                val result = controller.admin.auth.dto.PermissionInfoVO(controller.admin.auth.dto.UserInfoVO(identity.id, "sticker-test", "Sticker test"),
                    identity.roles.toList(), identity.permissions.toList(), rows.filter { it.long("parent_id") == 0L }.map { menu(it) })
                return JsonContent(Json.encodeToString(result))
            }
        }, routeGroup = "admin", requireAuth = true)
        fun token(id: Long, realm: String, type: String = "access") = jwt.createToken(UserId(id.toULong()),
            expiresInSeconds = 4 * 3600, extraClaims = mapOf("realm" to realm, "type" to type, "fixture_nonce" to Random.nextLong().toString()))
        val credentials = buildJsonObject {
            put("BASE_URL", "$origin/app"); put("ORIGIN", origin)
            put("baseUrl", "$origin/app")
            put("database", info.database); put("databasePort", info.port)
            put("ADMIN", token(9001, "admin")); put("READONLY", token(9002, "admin"))
            put("REFRESH", token(9001, "admin", "refresh"))
            put("ACCOUNT", token(9101, "account")); put("ACCOUNT_B", token(9102, "account"))
            put("ACCOUNT_RELOGIN", token(9101, "account"))
            putJsonArray("accounts") {
                for (id in (9101L..9132L).toList() + additionalAccounts) add(buildJsonObject {
                    put("uid", id.toString()); put("token", token(id, "account"))
                })
            }
            putJsonArray("accountPairs") {
                repeat(8) { index -> add(buildJsonObject {
                    put("pair", index)
                    putJsonObject("A") { put("uid", (9101L + index * 2).toString()); put("token", token(9101L + index * 2, "account")) }
                    putJsonObject("B") { put("uid", (9102L + index * 2).toString()); put("token", token(9102L + index * 2, "account")) }
                })
                }
            }
            put("backendAcceptanceAccount", token(9191, "account"))
            put("backendAcceptanceAccountB", token(9192, "account"))
            put("backendAcceptanceAccountRelogin", token(9191, "account"))
        }.toString()
        val path = privateHandoff(root, credentials)
        println("STICKER_PACK_HANDOFF=$path")
        signal(SIGPIPE, SIG_IGN)
        val adapter = DefaultHttpAdapter(HttpServerConfig(port = port, host = "127.0.0.1"),
            DefaultHttpOptions(reactors = 1, maxRequestBodyBytes = 34L * 1024 * 1024, maxBufferedRequestBytes = 68L * 1024 * 1024))
        val ready = CompletableDeferred<Unit>()
        val server = launch(Dispatchers.Default) { adapter.start(ctx) { ready.complete(Unit) } }
        server.invokeOnCompletion { if (it != null) ready.completeExceptionally(it) }
        try {
            withTimeout(15000) { ready.await() }
            println("STICKER_PACK_HTTP_READY=$origin")
            withTimeoutOrNull(4 * 3600 * 1000L) { while (access("$root/stop", F_OK) != 0) delay(250) }
        } finally { adapter.stop(); server.join(); SqlxDatabase.close(); unlink(path) }
    }

    private fun privateHandoff(root: String, content: String): String {
        val template = "$root/handoff-XXXXXX\u0000".encodeToByteArray()
        val fd = template.usePinned { mkstemp(it.addressOf(0)) }; check(fd >= 0)
        try {
            check(fchmod(fd, 384u.convert()) == 0)
            val bytes = content.encodeToByteArray()
            bytes.usePinned { p ->
                var offset = 0
                while (offset < bytes.size) {
                    val n = write(fd, p.addressOf(offset), (bytes.size - offset).convert()).toInt()
                    check(n > 0); offset += n
                }
            }
        } finally { close(fd) }
        return template.decodeToString().trimEnd('\u0000')
    }
}
