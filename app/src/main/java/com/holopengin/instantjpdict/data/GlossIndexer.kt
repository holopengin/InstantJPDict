package com.holopengin.instantjpdict.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.holopengin.instantjpdict.util.Definitions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * #108 follow-up: build the English gloss index for dictionaries imported
 * before the FTS table existed.
 *
 * New imports write [DictionaryGloss] rows as they insert entries. Existing
 * installs have `glossIndexed = false`, so the first English lookup fills the
 * table once and then flips the flag. The JSON `definitions` column stays the
 * source of truth; the index is derived and can be rebuilt.
 */
object GlossIndexer {

    private const val TAG = "GlossIndexer"
    private const val BATCH = 4_000

    /** One index build at a time: two lookups must not duplicate rows. */
    private val mutex = Mutex()

    /**
     * The index build is a background job, not a prerequisite for answering a
     * lookup. A large existing dictionary can take tens of seconds to index;
     * making the first query wait for that looked like a broken search, so the
     * provider starts this and immediately falls back to the scan if the index
     * is not ready yet.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var buildJob: Job? = null

    /** True while a build is writing rows. Queries use the scan meanwhile. */
    fun isIndexing(): Boolean = buildJob?.isActive == true

    fun requestIndex(context: Context) {
        if (buildJob?.isActive == true) return
        synchronized(this) {
            if (buildJob?.isActive == true) return
            val appContext = context.applicationContext
            buildJob = scope.launch {
                try {
                    ensureIndexed(appContext)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "English index build failed", t)
                    main.post {
                        Toast.makeText(
                            appContext,
                            "English index failed; search will use the slow scan",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    suspend fun ensureIndexed(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = AppDatabase.getDatabase(context)
            val dao = db.dictionaryDao()
            val pending = dao.dictionariesToIndex()
            if (pending.isEmpty()) return@withLock

            val started = System.currentTimeMillis()
            val built = mutableListOf<Int>()
            for (dictionary in pending) {
                // A failed earlier attempt may have left a partial table.
                dao.deleteGlossesForDictionary(dictionary.id)
                var offset = 0
                while (true) {
                    val rows = dao.glossSourceRows(dictionary.id, BATCH, offset)
                    if (rows.isEmpty()) break
                    val plain = Definitions.plainRows(rows.map { it.definitions })
                    check(plain.size == rows.size) {
                        "plainRows returned ${plain.size} values for ${rows.size} rows"
                    }
                    dao.insertGlosses(
                        rows.mapIndexed { index, row ->
                            DictionaryGloss(
                                entryId = row.id,
                                dictionaryId = row.dictionaryId,
                                text = plain[index],
                            )
                        },
                    )
                    offset += rows.size
                }
                built += dictionary.id
            }

            // The migration creates the FTS table without triggers; a rebuild
            // is the one place that keeps it in step with the content table.
            // Flags are set only after the rebuild, so a process death between
            // the two cannot leave an "indexed" dictionary with a stale FTS.
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO dictionary_gloss_fts(dictionary_gloss_fts) VALUES('rebuild')"
            )
            for (dictionaryId in built) dao.setGlossIndexed(dictionaryId, true)
            Log.i(
                TAG,
                "indexed ${built.size} dictionaries in ${System.currentTimeMillis() - started}ms",
            )
        }
    }

    /**
     * Rebuild after an import has written its gloss rows. Imports run on the
     * IO dispatcher, so the synchronous SQL call is safe here.
     */
    suspend fun rebuild(db: AppDatabase) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO dictionary_gloss_fts(dictionary_gloss_fts) VALUES('rebuild')"
            )
        }
    }
}
