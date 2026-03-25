package dev.duma.android.hal.service.auth

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for storing session tokens. Single table ([TokenEntity])
 * with CRUD via [TokenDao]. Uses WAL mode by default for concurrent access.
 */
@Database(entities = [TokenEntity::class], version = 1, exportSchema = false)
abstract class TokenDatabase : RoomDatabase() {
    abstract fun tokenDao(): TokenDao
}
