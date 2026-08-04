package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY displayName ASC")
    fun getAllGamesFlow(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games ORDER BY displayName ASC")
    suspend fun getAllGames(): List<GameEntity>

    @Query("SELECT displayName FROM games WHERE packageName = :packageName")
    suspend fun getDisplayName(packageName: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGame(game: GameEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGames(games: List<GameEntity>)

    @Delete
    suspend fun deleteGame(game: GameEntity)

    @Query("DELETE FROM games")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int
}
