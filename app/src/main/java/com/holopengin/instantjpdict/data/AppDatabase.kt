package com.holopengin.instantjpdict.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DictionaryEntry::class, DictionaryMeta::class, DictionaryTag::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * #43: `builtIn` on dictionary_meta. Written by hand to match Room's
         * generated DDL (Booleans are INTEGER NOT NULL) — without it the
         * destructive fallback would wipe every user-imported dictionary on
         * upgrade, which is a much worse outcome than a failed migration.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `dictionary_meta` ADD COLUMN `builtIn` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "instant_jp_dict_db"
                ).addMigrations(MIGRATION_3_4)
                    // F5/#86: the explicit parameter the no-arg overload was replaced
                    // by; `true` is the old behaviour (drop every table on a schema
                    // mismatch). The hand-written MIGRATION_3_4 above is what kept
                    // user-imported dictionaries across the version-4 upgrade.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
