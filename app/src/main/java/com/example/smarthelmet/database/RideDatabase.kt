package com.example.smarthelmet.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [RideEntity::class], version = 2, exportSchema = false)
@TypeConverters(PointTypeConverter::class)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        @Volatile
        private var INSTANCE: RideDatabase? = null

        fun getDatabase(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "smart_helmet_database"
                )
                    .fallbackToDestructiveMigration() // Safely clears old DB to match new schema
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}