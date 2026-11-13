package com.mbk.halatuju.core.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "recent_places")
data class RecentPlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val lastUsedEpochMs: Long
)

@Entity(tableName = "favorite_places")
data class FavoritePlaceEntity(
    @PrimaryKey val slotKey: String,
    val placeId: String,
    val name: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val kindName: String,
    val updatedEpochMs: Long
)

@Dao
interface PlaceDao {
    @Query("SELECT * FROM recent_places ORDER BY lastUsedEpochMs DESC LIMIT :limit")
    fun observeRecents(limit: Int = 20): Flow<List<RecentPlaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecent(entity: RecentPlaceEntity)

    @Query("DELETE FROM recent_places WHERE id NOT IN (SELECT id FROM recent_places ORDER BY lastUsedEpochMs DESC LIMIT :keepLimit)")
    suspend fun trimRecents(keepLimit: Int = 30)

    @Query("DELETE FROM recent_places")
    suspend fun clearRecents()

    @Query("SELECT * FROM favorite_places ORDER BY CASE WHEN kindName = 'HOME' THEN 0 WHEN kindName = 'WORK' THEN 1 ELSE 2 END, updatedEpochMs DESC")
    fun observeFavorites(): Flow<List<FavoritePlaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(entity: FavoritePlaceEntity)

    @Query("DELETE FROM favorite_places WHERE slotKey = :slotKey")
    suspend fun deleteFavorite(slotKey: String)
}

@Database(
    entities = [RecentPlaceEntity::class, FavoritePlaceEntity::class],
    version = 1,
    exportSchema = true
)
abstract class HalaTujuDatabase : RoomDatabase() {
    abstract fun placeDao(): PlaceDao
}

interface PlaceRepository {
    fun observeRecents(limit: Int = 15): Flow<List<Place>>
    fun observeFavorites(): Flow<List<Place>>
    suspend fun recordRecent(place: Place, usedAtEpochMs: Long = System.currentTimeMillis())
    suspend fun saveFavorite(place: Place, kind: FavoriteKind, updatedAtEpochMs: Long = System.currentTimeMillis())
    suspend fun removeFavorite(kindOrId: String)
    suspend fun clearRecents()
}

@Singleton
class RoomPlaceRepository @Inject constructor(
    private val dao: PlaceDao
) : PlaceRepository {

    override fun observeRecents(limit: Int): Flow<List<Place>> {
        return dao.observeRecents(limit).map { rows ->
            rows.map { row ->
                Place(
                    id = row.id,
                    name = row.name,
                    subtitle = row.subtitle,
                    location = LatLng(row.latitude, row.longitude),
                    favoriteKind = FavoriteKind.NONE,
                    lastUsedEpochMs = row.lastUsedEpochMs
                )
            }
        }
    }

    override fun observeFavorites(): Flow<List<Place>> {
        return dao.observeFavorites().map { rows ->
            rows.map { row ->
                val kind = FavoriteKind.entries.firstOrNull { it.name == row.kindName } ?: FavoriteKind.NONE
                Place(
                    id = row.placeId,
                    name = row.name,
                    subtitle = row.subtitle,
                    location = LatLng(row.latitude, row.longitude),
                    favoriteKind = kind,
                    lastUsedEpochMs = row.updatedEpochMs
                )
            }
        }
    }

    override suspend fun recordRecent(place: Place, usedAtEpochMs: Long) {
        dao.upsertRecent(
            RecentPlaceEntity(
                id = place.id,
                name = place.name,
                subtitle = place.subtitle,
                latitude = place.location.latitude,
                longitude = place.location.longitude,
                lastUsedEpochMs = usedAtEpochMs
            )
        )
        dao.trimRecents(keepLimit = 30)
    }

    override suspend fun saveFavorite(place: Place, kind: FavoriteKind, updatedAtEpochMs: Long) {
        val slotKey = when (kind) {
            FavoriteKind.HOME -> "HOME"
            FavoriteKind.WORK -> "WORK"
            FavoriteKind.NONE -> "SAVED:${place.id}"
        }
        dao.upsertFavorite(
            FavoritePlaceEntity(
                slotKey = slotKey,
                placeId = place.id,
                name = place.name,
                subtitle = place.subtitle,
                latitude = place.location.latitude,
                longitude = place.location.longitude,
                kindName = kind.name,
                updatedEpochMs = updatedAtEpochMs
            )
        )
    }

    override suspend fun removeFavorite(kindOrId: String) {
        dao.deleteFavorite(kindOrId)
        dao.deleteFavorite("SAVED:$kindOrId")
    }

    override suspend fun clearRecents() {
        dao.clearRecents()
    }
}
