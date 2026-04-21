package com.bodyscalereader.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    
    @Insert
    suspend fun insert(measurement: Measurement)
    
    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    suspend fun getAll(): List<Measurement>
    
    @Query("DELETE FROM measurements")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM measurements WHERE timestamp >= :startDate ORDER BY timestamp DESC")
    suspend fun getSince(startDate: Long): List<Measurement>
}