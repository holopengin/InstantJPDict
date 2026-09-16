package com.holopengin.instantjpdict.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DictionaryEntry::class, DictionaryMeta::class, DictionaryTag::class, Bookmark::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun bookmarkDao(): BookmarkDao

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

        /**
         * #67: the bookmark table. Written by hand for exactly the reason
         * [MIGRATION_3_4] was: `fallbackToDestructiveMigration` would otherwise
         * wipe every user-imported dictionary to add a table that touches none
         * of them. The DDL lives in [BookmarkSchema] so a unit test can pin it
         * and the migration cannot silently drift from the entity.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(BookmarkSchema.CREATE_TABLE)
                db.execSQL(BookmarkSchema.CREATE_UNIQUE_INDEX)
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "instant_jp_dict_db"
                ).addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    // F5/#86: the explicit parameter the no-arg overload was replaced
                    // by; `true` is the old behaviour (drop every table on a schema
                    // mismatch). The hand-written migrations above are what kept
                    // user-imported dictionaries across the version-4 and version-5
                    // upgrades.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
