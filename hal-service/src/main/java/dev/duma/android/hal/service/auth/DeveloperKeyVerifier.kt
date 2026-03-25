package dev.duma.android.hal.service.auth

import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import dev.duma.android.hal.transport.core.CallerContext
import java.util.Date

/**
 * Verifies developer key JWTs using an RSA public key. Checks signature validity,
 * expiration, and restriction matching (package name for Android, origins for web).
 * Returns [VerificationResult] with extracted claims or a specific error.
 */
class DeveloperKeyVerifier(private val publicKey: RSAKey) {

    private val verifier: JWSVerifier = RSASSAVerifier(publicKey)

    fun verify(jwt: String, callerContext: CallerContext): VerificationResult {
        val signedJwt = try {
            SignedJWT.parse(jwt)
        } catch (_: Exception) {
            return VerificationResult.Error(DeveloperKeyError.INVALID_SIGNATURE)
        }

        // Verify signature
        if (!signedJwt.verify(verifier)) {
            return VerificationResult.Error(DeveloperKeyError.INVALID_SIGNATURE)
        }

        val claims = signedJwt.jwtClaimsSet

        // Check expiration
        val exp = claims.expirationTime
        if (exp != null && exp.before(Date())) {
            return VerificationResult.Error(DeveloperKeyError.EXPIRED)
        }

        // Extract fields
        val permissions = claims.getStringListClaim("permissions") ?: emptyList()
        val clientType = claims.getStringClaim("client_type") ?: "unrestricted"
        val restrictions = claims.getJSONObjectClaim("restrictions")

        val packageName = restrictions?.get("package_name") as? String
        val certHash = restrictions?.get("cert_sha256") as? String
        @Suppress("UNCHECKED_CAST")
        val origins = restrictions?.get("origins") as? List<String>

        // Check restrictions
        when (clientType) {
            "android" -> {
                if (packageName != null && callerContext.packageName != null) {
                    if (callerContext.packageName != packageName) {
                        return VerificationResult.Error(DeveloperKeyError.RESTRICTION_MISMATCH)
                    }
                }
                if (certHash != null && callerContext.certHash != null) {
                    if (callerContext.certHash != certHash) {
                        return VerificationResult.Error(DeveloperKeyError.RESTRICTION_MISMATCH)
                    }
                }
            }
            "web" -> {
                if (origins != null && callerContext.origin != null) {
                    if (callerContext.origin !in origins) {
                        return VerificationResult.Error(DeveloperKeyError.RESTRICTION_MISMATCH)
                    }
                }
            }
            "unrestricted" -> { /* no restrictions */ }
        }

        return VerificationResult.Success(
            DevKeyClaims(
                permissions = permissions,
                clientType = clientType,
                packageName = packageName,
                certHash = certHash,
                origins = origins
            )
        )
    }
}
