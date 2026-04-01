package dev.duma.android.hal.service.auth

import dev.duma.android.hal.transport.core.CallerContext
import java.security.SecureRandom

/**
 * Manages session token lifecycle: creation, validation (with binding checks),
 * revocation, and cleanup of expired tokens. Backed by Room via [TokenDao].
 * Thread-safe — Room handles concurrent access internally.
 */
class TokenManager(private val dao: TokenDao) {

    private val secureRandom = SecureRandom()

    fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun createToken(
        clientId: String,
        permissions: List<String>,
        grantedBy: String,
        duration: String,
        boundPackageName: String?,
        boundCertHash: String?,
        boundOrigin: String?
    ): TokenEntity {
        val now = System.currentTimeMillis()
        val expiresAt = when (duration) {
            "permanent" -> null
            "day" -> now + 24 * 60 * 60 * 1000L
            else -> now + 24 * 60 * 60 * 1000L
        }

        val entity = TokenEntity(
            token = generateToken(),
            clientId = clientId,
            clientType = when {
                boundPackageName != null || boundCertHash != null -> "android"
                boundOrigin != null -> "web"
                else -> "unrestricted"
            },
            permissions = permissions.joinToString(","),
            grantedBy = grantedBy,
            grantedAt = now,
            expiresAt = expiresAt,
            boundPackageName = boundPackageName,
            boundCertHash = boundCertHash,
            boundOrigin = boundOrigin
        )

        dao.insert(entity)
        return entity
    }

    suspend fun validateToken(token: String, callerContext: CallerContext): TokenEntity? {
        val entity = dao.getByToken(token) ?: return null

        // Check expiry
        if (entity.expiresAt != null && entity.expiresAt < System.currentTimeMillis()) {
            return null
        }

        // Check binding — unrestricted tokens (no bound fields) pass from any context
        if (entity.boundPackageName != null) {
            if (callerContext.packageName != entity.boundPackageName) return null
        }
        if (entity.boundCertHash != null) {
            if (callerContext.certHash != entity.boundCertHash) return null
        }
        if (entity.boundOrigin != null) {
            if (callerContext.origin != entity.boundOrigin) return null
        }

        return entity
    }

    suspend fun revokeToken(token: String) {
        dao.deleteByToken(token)
    }

    suspend fun revokeAllForClient(clientId: String) {
        dao.deleteByClientId(clientId)
    }

    suspend fun findExistingToken(
        clientId: String,
        grantedBy: String,
        requiredPermissions: List<String>,
        boundPackageName: String?,
        boundCertHash: String?,
        boundOrigin: String?
    ): TokenEntity? {
        val candidates = dao.findCandidateTokens(
            clientId, grantedBy, boundPackageName, boundCertHash, boundOrigin,
            System.currentTimeMillis()
        )
        return candidates.firstOrNull { permissionsAreSufficient(it.permissions, requiredPermissions) }
    }

    private fun permissionsAreSufficient(stored: String, required: List<String>): Boolean {
        val granted = stored.split(",")
        if ("*" in granted) return true
        return required.all { req -> granted.any { req.startsWith(it) } }
    }

    suspend fun cleanExpired() {
        dao.deleteExpired(System.currentTimeMillis())
    }
}
