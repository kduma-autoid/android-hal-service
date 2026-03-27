package dev.duma.android.hal.service.auth

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an issued session token. Stores token value, permissions,
 * binding constraints (package/cert/origin), and expiry. Used by [TokenManager]
 * for token CRUD and validation.
 */
@Entity(
    tableName = "tokens",
    indices = [Index(value = ["token"], unique = true)]
)
data class TokenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val token: String,
    val clientId: String,
    val clientType: String,
    val permissions: String,
    val grantedBy: String,
    val grantedAt: Long,
    val expiresAt: Long?,
    val boundPackageName: String?,
    val boundCertHash: String?,
    val boundOrigin: String?,
    @ColumnInfo(defaultValue = "{}") val clientInfo: String = "{}"
)
