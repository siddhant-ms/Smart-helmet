package com.example.smarthelmet.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Still version 3 on purpose: speedHistory's column stays TEXT either way, only what's
// encoded inside it changed. Room's schema check is based on table/column shape, not
// converter internals, so this doesn't need a new migration. Worth a real-device check
// on an existing v3 install before you ship it, just to be safe.
@Database(entities = [RideEntity::class], version = 3, exportSchema = false)
@TypeConverters(PointTypeConverter::class, TelemetryHistoryConverter::class)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        @Volatile
        private var INSTANCE: RideDatabase? = null

        // Adds the speedHistory column as TEXT, defaulting to "" so existing rows
        // (and PointTypeConverter-style string columns already on the table) are untouched.
        // "" round-trips through TelemetryHistoryConverter.toTelemetryHistory() to emptyList().
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ride_history ADD COLUMN speedHistory TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getDatabase(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "smart_helmet_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    // Kept only as a safety net for schema jumps with no defined migration
                    // (e.g. dev builds that skipped a version). The explicit migration above
                    // is what actually protects saved rides on the 2 -> 3 upgrade path.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}