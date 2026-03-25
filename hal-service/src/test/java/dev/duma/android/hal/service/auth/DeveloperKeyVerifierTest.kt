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
        clientType: String = "unrestricted",
        packageName: String? = null,
        certHash: String? = null,
        origins: List<String>? = null,
        exp: Date? = Date(System.currentTimeMillis() + 3600_000),
        signingKey: RSAKey = keyPair
    ): String {
        val restrictions = mutableMapOf<String, Any?>()
        packageName?.let { restrictions["package_name"] = it }
        certHash?.let { restrictions["cert_sha256"] = it }
        origins?.let { restrictions["origins"] = it }

        val claimsBuilder = JWTClaimsSet.Builder()
            .issuer("hal-developer-portal")
            .subject("test-client")
            .issueTime(Date())
            .claim("permissions", permissions)
            .claim("client_type", clientType)
            .claim("restrictions", restrictions)

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
        ))
        assertIs<VerificationResult.Success>(result)
        assertEquals(listOf("printer", "scanner"), result.claims.permissions)
    }

    @Test
    fun `expired JWT returns error`() {
        val jwt = createTestJwt(exp = Date(System.currentTimeMillis() - 10_000))
        val result = verifier.verify(jwt, CallerContext(transport = "aidl"))
        assertIs<VerificationResult.Error>(result)
        assertEquals(DeveloperKeyError.EXPIRED, result.error)
    }

    @Test
    fun `wrong signature returns error`() {
        val jwt = createTestJwt(signingKey = wrongKeyPair)
        val result = verifier.verify(jwt, CallerContext(transport = "aidl"))
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
        ))
        assertIs<VerificationResult.Error>(result)
        assertEquals(DeveloperKeyError.RESTRICTION_MISMATCH, result.error)
    }

    @Test
    fun `unrestricted JWT works from any context`() {
        val jwt = createTestJwt(clientType = "unrestricted")
        val result = verifier.verify(jwt, CallerContext(
            transport = "ws", origin = "https://anything.com"
        ))
        assertIs<VerificationResult.Success>(result)
        assertNotNull(result.claims)
    }

    @Test
    fun `web JWT checks origin`() {
        val jwt = createTestJwt(
            clientType = "web",
            origins = listOf("https://myapp.com")
        )

        val validResult = verifier.verify(jwt, CallerContext(
            transport = "http", origin = "https://myapp.com"
        ))
        assertIs<VerificationResult.Success>(validResult)

        val invalidResult = verifier.verify(jwt, CallerContext(
            transport = "http", origin = "https://evil.com"
        ))
        assertIs<VerificationResult.Error>(invalidResult)
        assertEquals(DeveloperKeyError.RESTRICTION_MISMATCH, invalidResult.error)
    }
}
