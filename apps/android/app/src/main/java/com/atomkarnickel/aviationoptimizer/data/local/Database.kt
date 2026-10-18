package com.atomkarnickel.aviationoptimizer.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Gemerkte Reise inkl. Snapshot des Itineraries (JSON), damit sie auch offline angezeigt werden kann. */
@Entity(tableName = "favorite_trips")
data class FavoriteTripEntity(
    @PrimaryKey val itineraryId: String,
    val searchId: String,
    val savedAtMillis: Long,
    val score: Double,
    val summary: String,
    val itineraryJson: String,
)

/** Persönliche Markierung eines Flugzeugtyps (Wunschliste, geflogen, uninteressant) und eigene Bewertung. */
@Entity(tableName = "favorite_aircraft")
data class AircraftPreferenceEntity(
    @PrimaryKey val aircraftTypeId: String,
    val status: String,
    val personalRating: Double?,
)

@Entity(tableName = "flight_log")
data class FlightLogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val flightNumber: String,
    val airline: String,
    val origin: String,
    val destination: String,
    val aircraftTypeId: String,
    val status: String,
    val notes: String,
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_trips ORDER BY savedAtMillis DESC")
    fun trips(): Flow<List<FavoriteTripEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: FavoriteTripEntity)

    @Query("DELETE FROM favorite_trips WHERE itineraryId = :id")
    suspend fun deleteTrip(id: String)

    @Query("SELECT * FROM favorite_aircraft")
    fun aircraft(): Flow<List<AircraftPreferenceEntity>>

    @Upsert
    suspend fun upsertAircraft(pref: AircraftPreferenceEntity)

    @Query("DELETE FROM favorite_aircraft WHERE aircraftTypeId = :id")
    suspend fun deleteAircraft(id: String)
}

@Dao
interface FlightLogDao {
    @Query("SELECT * FROM flight_log ORDER BY date DESC, id DESC")
    fun entries(): Flow<List<FlightLogEntryEntity>>

    @Insert
    suspend fun insert(entry: FlightLogEntryEntity): Long

    @Query("DELETE FROM flight_log WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * Schemaänderungen: Version erhöhen und eine [androidx.room.migration.Migration] in AppModule registrieren.
 * Das exportierte Schema liegt unter app/schemas/ und wird versioniert.
 */
@Database(
    entities = [FavoriteTripEntity::class, AircraftPreferenceEntity::class, FlightLogEntryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun flightLogDao(): FlightLogDao
}
