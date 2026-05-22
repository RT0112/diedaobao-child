package com.familyguardian.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FallNotification::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fallNotificationDao(): FallNotificationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fall_notifications ADD COLUMN ffDuration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE fall_notifications ADD COLUMN physicalScore REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE fall_notifications ADD COLUMN weightedScore REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE fall_notifications ADD COLUMN decisionPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE fall_notifications ADD COLUMN sensorDataJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE fall_notifications ADD COLUMN feedRate REAL NOT NULL DEFAULT 0.0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "family_guardian.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
