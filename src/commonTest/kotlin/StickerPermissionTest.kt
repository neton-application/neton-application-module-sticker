import kotlinx.coroutines.runBlocking
import neton.core.interfaces.RequestContext
import neton.security.identity.UserId
import neton.security.jwt.JwtAuthenticator
import neton.security.jwt.VerifiedJwtToken
import security.*
import kotlin.test.*

/** Tests shared realm enforcement on sticker URLs, not a replacement HTTP/auth implementation. */
class StickerPermissionTest {
    @Test fun accountRefreshAndRealmLessCannotReachAdministration() = runBlocking {
        val jwt=JwtAuthenticator(secretKey="test-sticker-secret-0123456789abcdef-0123456789") // gitleaks:allow -- isolated unit-test fixture, never a deployed signing key
        val registry=TokenRealmRegistry().apply { register(object: TokenRealm {
            override val name="admin"
            override val acceptsExternalTokens=false
            override val routeGroups=setOf("admin")
            override suspend fun resolve(token: VerifiedJwtToken) = token.identity.takeIf { token.claimString(TokenRealm.TYPE_CLAIM)==TokenRealm.TYPE_ACCESS }
        }) }
        val auth=RealmAuthenticator(jwt,null,registry,"Authorization","Bearer ","neton_session")
        for(path in listOf("pack/import","pack/make","pack/publish","author/create","author/update")) {
            fun request(token: String)=object: RequestContext {
                override val path="/admin/sticker/$path"
                override val method="POST"
                override val headers=mapOf("Authorization" to "Bearer $token")
                override val routeGroup="admin"
            }
            for((realm,type) in listOf("account" to "access","admin" to "refresh","" to "access")) {
                val token=jwt.createToken(UserId(1u),expiresInSeconds=600,extraClaims=mapOf("realm" to realm,"type" to type))
                assertNull(auth.authenticate(request(token)))
            }
            val token=jwt.createToken(UserId(1u),expiresInSeconds=600,extraClaims=mapOf("realm" to "admin","type" to "access"))
            assertNotNull(auth.authenticate(request(token)))
        }
    }
    @Test fun queryOnlyAdminCannotWrite() {
        val actor=object: neton.core.interfaces.Identity {
            override val id="1"
            override val roles=emptySet<String>()
            override val permissions=setOf("sticker:pack:query")
        }
        val request=object: RequestContext {
            override val path="/admin/sticker/pack/import"
            override val method="POST"
            override val headers=emptyMap<String,String>()
            override val routeGroup="admin"
        }
        val evaluator=WildcardPermissionEvaluator()
        assertTrue(evaluator.allowed(actor,"sticker:pack:query",request))
        for(action in listOf("import","make","publish","offline","create","update","delete")) assertFalse(evaluator.allowed(actor,"sticker:pack:$action",request))
        assertFalse(evaluator.allowed(actor,"sticker:author:create",request))
    }
}
