package dev.duma.android.hal.service.auth

import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jwt.SignedJWT
import dev.duma.android.hal.transport.core.CallerContext
import java.util.Date

/**
 * Verifies service key JWTs using an RSA public key. Checks signature validity,
 * expiration, and restriction matching (package name for Android, origins for web).
 * Returns [VerificationResult] with extracted claims or a specific error.
 */
class ServiceKeyVerifier(
    private val verifier: JWSVerifier,
    private val requiredIssuer: String? = null
) {

    fun verify(jwt: String, callerContext: CallerContext, requestClientId: String): VerificationResult {
        val signedJwt = try {
            SignedJWT.parse(jwt)
        } catch (_: Exception) {
            return VerificationResult.Error(ServiceKeyError.INVALID_SIGNATURE)
        }

        // Verify type
        if (signedJwt.header.type?.type != "hal-service-key+jwt") {
            return VerificationResult.Error(ServiceKeyError.INVALID_SIGNATURE)
        }

        // Verify signature (catch algorithm mismatch, e.g. HS256 JWT vs RSA verifier)
        val signatureValid = try {
            signedJwt.verify(verifier)
        } catch (_: Exception) {
            false
        }
        if (!signatureValid) {
            return VerificationResult.Error(ServiceKeyError.INVALID_SIGNATURE)
        }

        val claims = signedJwt.jwtClaimsSet

        // Check issuer if required
        if (requiredIssuer != null) {
            val iss = claims.issuer
            if (iss != null && iss != requiredIssuer) {
                return VerificationResult.Error(ServiceKeyError.INVALID_SIGNATURE)
            }
        }

        // Check expiration
        val exp = claims.expirationTime
        if (exp != null && exp.before(Date())) {
            return VerificationResult.Error(ServiceKeyError.EXPIRED)
        }

        // Extract fields
        val basePermissions = (claims.getStringListClaim("permissions") ?: emptyList())
            .filterNot { it == "experimental" || it.endsWith(".experimental") }
            .filterNot { it == "super" || it.endsWith(".super") }
        val allowedClientTypes: List<String> = when (val raw = claims.getClaim("client_type")) {
            is String -> listOf(raw)
            is List<*> -> @Suppress("UNCHECKED_CAST") (raw as List<String>)
            else -> listOf("unrestricted")
        }
        val restrictions = claims.getJSONObjectClaim("restrictions")

        // Parse experimental claim → add to permissions
        val experimentalPerms: List<String> = try {
            val boolVal = claims.getBooleanClaim("experimental")
            if (boolVal == true) listOf("experimental") else emptyList()
        } catch (_: Exception) {
            try {
                val caps = claims.getStringListClaim("experimental")
                caps?.map { "$it.experimental" } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        // Parse super claim → add to permissions
        val superPerms: List<String> = try {
            val boolVal = claims.getBooleanClaim("super")
            if (boolVal == true) listOf("super") else emptyList()
        } catch (_: Exception) {
            try {
                val caps = claims.getStringListClaim("super")
                caps?.map { "$it.super" } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        val permissions = basePermissions + experimentalPerms + superPerms

        val clientId = restrictions?.get("client_id") as? String

        @Suppress("UNCHECKED_CAST")
        val androidRestrictions = restrictions?.get("android") as? Map<String, Any?>
        val packageName = androidRestrictions?.get("package_name") as? String
        val certHash = androidRestrictions?.get("cert_sha256") as? String

        @Suppress("UNCHECKED_CAST")
        val webRestrictions = restrictions?.get("web") as? Map<String, Any?>
        val origins: List<String>? = when (val raw = webRestrictions?.get("origins")) {
            is List<*> -> @Suppress("UNCHECKED_CAST") (raw as List<String>)
            is String -> listOf(raw)
            else -> null
        }

        // Check restrictions
        if (clientId != null && clientId != requestClientId) {
            return VerificationResult.Error(ServiceKeyError.RESTRICTION_MISMATCH)
        }

        // Determine caller's effective type from transport
        val callerType = when (callerContext.transport) {
            "aidl", "intent" -> "android"
            "http", "ws" -> "web"
            else -> null
        }

        // Check caller type is allowed (skip if unrestricted)
        if ("unrestricted" !in allowedClientTypes) {
            if (callerType == null || callerType !in allowedClientTypes) {
                return VerificationResult.Error(ServiceKeyError.RESTRICTION_MISMATCH)
            }
        }

        // Apply type-specific restrictions based on caller's actual type (fields optional)
        when (callerType) {
            "android" -> {
                if (packageName != null && callerContext.packageName != null
                    && callerContext.packageName != packageName) {
                    return VerificationResult.Error(ServiceKeyError.RESTRICTION_MISMATCH)
                }
                if (certHash != null && callerContext.certHash != null
                    && callerContext.certHash != certHash) {
                    return VerificationResult.Error(ServiceKeyError.RESTRICTION_MISMATCH)
                }
            }
            "web" -> {
                if (origins != null && callerContext.origin != null
                    && callerContext.origin !in origins) {
                    return VerificationResult.Error(ServiceKeyError.RESTRICTION_MISMATCH)
                }
            }
        }

        return VerificationResult.Success(
            ServiceKeyClaims(
                permissions = permissions,
                clientTypes = allowedClientTypes,
                clientId = clientId,
                packageName = packageName,
                certHash = certHash,
                origins = origins
            )
        )
    }
}
