package com.example.smarthelmet.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlin.jvm.JvmSuppressWildcards

@Dao
@JvmSuppressWildcards
interface RideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideEntity): Long

    @Query("SELECT * FROM ride_history ORDER BY startTime DESC")
    suspend fun getAllRides(): List<RideEntity>

    // This is the new function that was missing
    @Query("DELETE FROM ride_history WHERE id = :rideId")
    suspend fun deleteRideById(rideId: Long) : Int
}