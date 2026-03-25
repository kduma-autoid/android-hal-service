package dev.duma.android.hal.service.auth

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Room DAO for token storage operations. Provides queries for token lookup,
 * creation, deletion, and cleanup of expired tokens.
 */
@Dao
interface TokenDao {
    @Query("SELECT * FROM tokens WHERE token = :token LIMIT 1")
    suspend fun getByToken(token: String): TokenEntity?

    @Query("SELECT * FROM tokens")
    suspend fun getAll(): List<TokenEntity>

    @Insert
    suspend fun insert(entity: TokenEntity): Long

    @Query("DELETE FROM tokens WHERE token = :token")
    suspend fun deleteByToken(token: String)

    @Query("DELETE FROM tokens WHERE clientId = :clientId")
    suspend fun deleteByClientId(clientId: String)

    @Query("DELETE FROM tokens WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: Long)

    @Query("UPDATE tokens SET expiresAt = :expiresAt WHERE token = :token")
    suspend fun updateExpiry(token: String, expiresAt: Long)
}
