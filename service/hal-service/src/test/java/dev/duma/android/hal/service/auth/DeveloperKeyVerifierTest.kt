package dev.duma.android.hal.service.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.duma.android.hal.transport.core.CallerContext
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Tests for [DeveloperKeyVerifier] — JWT signature verification, expiration checking,
 * and restriction matching for android/web/unrestricted client types.
 */
class DeveloperKeyVerifierTest {

    private val keyPair: RSAKey = RSAKeyGenerator(2048).generate()
    private val wrongKeyPair: RSAKey = RSAKeyGenerator(2048).generate()
    private val verifier = DeveloperKeyVerifier(keyPair.toPublicJWK())

    private fun createTestJwt(
        permissions: List<String> = listOf("printer"),
        clientType: Any = "unrestricted",
        clientId: String? = null,
        packageName: String? = null,
        certHash: String? = null,
        origins: List<String>? = null,
        exp: Date? = Date(System.currentTimeMillis() + 3600_000),
        signingKey: RSAKey = keyPair,
        extraClaims: Map<String, Any> = emptyMap()
    ): String {
        val restrictions = mutableMapOf<String, Any?>()
        clientId?.let { restrictions["client_id"] = it }
        if (packageName != null || certHash != null) {
            val android = mutableMapOf<String, Any?>()
            packageName?.let { android["package_name"] = it }
            certHash?.let { android["cert_sha256"] = it }
            restrictions["android"] = android
        }
        if (origins != null) {
            restrictions["web"] = mapOf("origins" to origins)
        }

        val claimsBuilder = JWTClaimsSet.Builder()
            .issuer("hal-developer-portal")
            .subject("test-client")
            .issueTime(Date())
            .claim("permissions", permissions)
            .claim("client_type", clientType)
            .claim("restrictions", restrictions)
        extraClaims.forEach { (key, value) -> claimsBuilder.claim(key, value) }

        if (exp != null) {
            claimsBuilder.expirationTime(exp)
        }

        val signedJwt = SignedJWT(
            JWSHeader(JWSAlgorithm.RS256),
            claimsBuilder.build()
        )
        signedJwt.sign(RSASSASigner(signingKey))
        return signedJwt.serialize()
    }

    @Test
    fun `valid JWT returns claims`() {
        val jwt = createTestJwt(
            permissions = listOf("printer", "scanner"),
            clientType = "android",
            packageName = "com.test.app"
        )
        val result = verifier.verify(jwt, CallerContext(
            transport = "aidl", packageName = "com.test.app"
        ), "test-client")
        assertIs<VerificationResult.Success>(result)
        assertEquals(listOf("printer", "scanner"), result.claims.permissions)
    }

    @Test
    fun `expired JWT returns error`() {
        val jwt = createTestJwt(exp = Date(System.currentTimeMillis() - 10_000))
        val result = verifier.verify(jwt, CallerContext(transport = "aidl"), "test-client")
        assertIs<VerificationResult.Error>(result)
        assertEquals(DeveloperKeyError.EXPIRED, result.error)
    }

    @Test
    fun `wrong signature returns error`() {
        val jwt = createTestJwt(signingKey = wrongKeyPair)
        val result = verifier.verify(jwt, CallerContext(transport = "aidl"), "test-client")
        assertIs<VerificationResult.Error>(result)
        assertEquals(DeveloperKeyError.INVALID_SIGNATURE, result.error)
    }

    @Test
    fun `restriction mismatch returns error`() {
        val jwt = createTestJwt(
            clientType = "android",
            packageName = "com.expected.app"
        )
        val result = verifier.verify(jwt, CallerContext(
            transport = "aidl", packageName = "com.wrong.app"
        ), "test-client")
        assertIs<VerificationResult.Error>(result)
        assertEquals(DeveloperKeyError.RESTRICTION_MISMATCH, result.error)
    }

    @Test
    fun `unrestricted JWT works from any context`() {
        val jwt = createTestJwt(clientType = "unrestricted")
        val result = verifier.verify(jwt, CallerContext(
            transport = "ws", origin = "https://anything.com"
        ), "test-client")
        assertIs<VerificationResult.Success>(result)
        assertNotNull(result.claims)
    }

    @Test
    fun `client_id restriction is validated against request clientId`() {
        val jwt = createTestJwt(clientId = "expected-client")

        val validResult = verifier.verify(jwt, CallerContext(transport = "aidl"), "expected-client")
        assertIs<VerificationResult.Success>(validResult)

        val invalidResult = verifier.verify(jwt, CallerContext(transport = "aidl"), "other-client")
        assertIs<VerificationResult.Error>(invalidResult)
        assertEquals(DeveloperKeyError.RESTRICTION_MISMATCH, invalidResult.error)
    }

    @Test
    fun `web JWT checks origin`() {
        val jwt = createTestJwt(
            clientType = "web",
            origins = listOf("https://myapp.com")
        )

        val validResult = verifier.verify(jwt, CallerContext(
            transport = "http", origin = "https://myapp.com"
        ), "test-client")
        assertIs<VerificationResult.Success>(validResult)

        val invalidResult = verifier.verify(jwt, CallerContext(
            transport = "http", origin = "https://evil.com"
        ), "test-client")
        assertIs<VerificationResult.Error>(invalidResult)
        assertEquals(DeveloperKeyError.RESTRICTION_MISMATCH, invalidResult.error)
    }

    @Test
    fun `super permissions in permissions claim are stripped`() {
        val jwt = createTestJwt(
            permissions = listOf("printer", "super", "scanner.super", "scanner")
        )
        val result = verifier.verify(jwt, CallerContext(transport = "aidl"), "test-client")
        assertIs<VerificationResult.Success>(result)
        assertEquals(listOf("printer", "scanner"), result.claims.permissions)
    }

    @Test
    fun `super true claim grants super permission`() {
        val jwt = createTestJwt(extraClaims = mapOf("super" to true))
        val result = verifier.verify(jwt, CallerContext(transport = "aidl"), "test-client")
        assertIs<VerificationResult.Success>(result)
        assertEquals(listOf("printer", "super"), result.claims.permissions)
    }

    @Test
    fun `super list claim grants scoped super permissions`() {
        val jwt = createTestJwt(extraClaims = mapOf("super" to listOf("printer", "scanner")))
        val result = verifier.verify(jwt, CallerContext(transport = "aidl"), "test-client")
        assertIs<VerificationResult.Success>(result)
        assertEquals(listOf("printer", "printer.super", "scanner.super"), result.claims.permissions)
    }

    @Test
    fun `experimental permissions in permissions claim are stripped`() {
        val jwt = createTestJwt(
            permissions = listOf("printer", "experimental", "scanner.experimental", "scanner")
        )
        val result = verifier.verify(jwt, CallerContext(transport = "aidl"), "test-client")
        assertIs<VerificationResult.Success>(result)
        assertEquals(listOf("printer", "scanner"), result.claims.permissions)
    }

    @Test
    fun `origins as string is treated as single-element list`() {
        val restrictions = mutableMapOf<String, Any?>("web" to mapOf("origins" to "https://myapp.com"))
        val claimsBuilder = JWTClaimsSet.Builder()
            .issuer("hal-developer-portal")
            .subject("test-client")
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 3600_000))
            .claim("permissions", listOf("printer"))
            .claim("client_type", "web")
            .claim("restrictions", restrictions)
        val signedJwt = SignedJWT(JWSHeader(JWSAlgorithm.RS256), claimsBuilder.build())
        signedJwt.sign(RSASSASigner(keyPair))
        val jwt = signedJwt.serialize()

        val validResult = verifier.verify(jwt, CallerContext(
            transport = "http", origin = "https://myapp.com"
        ), "test-client")
        assertIs<VerificationResult.Success>(validResult)

        val invalidResult = verifier.verify(jwt, CallerContext(
            transport = "http", origin = "https://evil.com"
        ), "test-client")
        assertIs<VerificationResult.Error>(invalidResult)
        assertEquals(DeveloperKeyError.RESTRICTION_MISMATCH, invalidResult.error)
    }

    @Test
    fun `web token used from android transport returns mismatch`() {
        val jwt = createTestJwt(clientType = "web")
        val result = verifier.verify(jwt, CallerContext(
            transport = "aidl", packageName = "com.test.app"
        ), "test-client")
        assertIs<VerificationResult.Error>(result)
        assertEquals(DeveloperKeyError.RESTRICTION_MISMATCH, result.error)
    }

    @Test
    fun `android token used from web transport returns mismatch`() {
        val jwt = createTestJwt(clientType = "android")
        val result = verifier.verify(jwt, CallerContext(
            transport = "http", origin = "https://myapp.com"
        ), "test-client")
        assertIs<VerificationResult.Error>(result)
        assertEquals(DeveloperKeyError.RESTRICTION_MISMATCH, result.error)
    }

    @Test
    fun `client_type as array allows multiple transport types`() {
        val jwt = createTestJwt(clientType = listOf("web", "android"))

        val androidResult = verifier.verify(jwt, CallerContext(
            transport = "aidl", packageName = "com.test.app"
        ), "test-client")
        assertIs<VerificationResult.Success>(androidResult)

        val webResult = verifier.verify(jwt, CallerContext(
            transport = "http", origin = "https://myapp.com"
        ), "test-client")
        assertIs<VerificationResult.Success>(webResult)
    }

    @Test
    fun `client_type as single-element array works`() {
        val jwt = createTestJwt(clientType = listOf("web"))

        val webResult = verifier.verify(jwt, CallerContext(
            transport = "ws", origin = "https://myapp.com"
        ), "test-client")
        assertIs<VerificationResult.Success>(webResult)

        val androidResult = verifier.verify(jwt, CallerContext(
            transport = "aidl"
        ), "test-client")
        assertIs<VerificationResult.Error>(androidResult)
        assertEquals(DeveloperKeyError.RESTRICTION_MISMATCH, androidResult.error)
    }
}
