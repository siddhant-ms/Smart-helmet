package com.example.smarthelmet.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RideEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(
    PointTypeConverter::class,
    TelemetryHistoryConverter::class
)
abstract class RideDatabase : RoomDatabase() {

    abstract fun rideDao(): RideDao

    companion object {

        @Volatile
        private var INSTANCE: RideDatabase? = null

        /**
         * Version 2 -> 3
         *
         * Adds the speedHistory column as TEXT.
         *
         * Existing rows receive an empty string, which is converted to
         * emptyList() by TelemetryHistoryConverter.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {

            override fun migrate(
                db: SupportSQLiteDatabase
            ) {
                db.execSQL(
                    "ALTER TABLE ride_history " +
                            "ADD COLUMN speedHistory TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getDatabase(
            context: Context
        ): RideDatabase {

            return INSTANCE ?: synchronized(this) {

                val instance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        RideDatabase::class.java,
                        "smart_helmet_database"
                    )
                        .addMigrations(
                            MIGRATION_2_3
                        )
                        /*
                         * Do not use fallbackToDestructiveMigration().
                         *
                         * A missing migration should fail loudly rather
                         * than silently deleting the rider's saved history.
                         */
                        .build()

                INSTANCE = instance

                instance
            }
        }
    }
}
