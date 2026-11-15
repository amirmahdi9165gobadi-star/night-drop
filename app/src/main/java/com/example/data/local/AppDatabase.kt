package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MacroStepEntity::class,
        DownloadTaskEntity::class,
        ExecutionLogEntity::class,
        StrategyEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun macroDao(): MacroDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun strategyDao(): StrategyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS execution_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        timestampMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE download_tasks ADD COLUMN queueOrder INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE macro_steps ADD COLUMN nodeText TEXT")
                db.execSQL("ALTER TABLE macro_steps ADD COLUMN contentDescription TEXT")
                db.execSQL("ALTER TABLE macro_steps ADD COLUMN viewIdResourceName TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS strategy_rankings (
                        id TEXT NOT NULL PRIMARY KEY,
                        goal TEXT NOT NULL,
                        strategyCode TEXT NOT NULL,
                        title TEXT NOT NULL,
                        successScore INTEGER NOT NULL,
                        successCount INTEGER NOT NULL,
                        failureCount INTEGER NOT NULL,
                        lastUpdatedMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nightdrop_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
