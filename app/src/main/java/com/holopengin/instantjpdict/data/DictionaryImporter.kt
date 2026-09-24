package com.holopengin.instantjpdict.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.nav_graph_core.yomitanParseIndexTitle
import uniffi.nav_graph_core.yomitanParseKanjiBank
import uniffi.nav_graph_core.yomitanParseTagBank
import uniffi.nav_graph_core.yomitanParseTermBank
import uniffi.nav_graph_core.yomitanParseTermMetaBank
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class DictionaryImporter(private val context: Context) {

    private companion object {
        const val TAG = "DictionaryImporter"
    }

    /**
     * [catalogId] is the catalog entry this import came from, when it did
     * (#71 follow-up). It is stored on the imported dictionary's meta row so
     * the catalog can distinguish variants that share an upstream title; the
     * file-picker path passes nothing and leaves it null.
     */
    suspend fun importZip(
        uri: android.net.Uri,
        fileName: String,
        catalogId: String? = null,
        onProgress: (Int) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // #71: re-importing the same dictionary must replace it, not stack a
            // second copy. The zip's declared title is read from a first open
            // (which stops at index.json) and any existing dictionary with that
            // title is removed before the real import — the same guarantee
            // [importBundledAsset] has always had, now shared by the file-picker
            // and catalog paths so both are idempotent.
            context.contentResolver.openInputStream(uri)?.use { stream ->
                readZipTitle(stream)?.let { replaceExisting(it) }
            }
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Failed to open input stream"))
            // A6/#86: `importZipStream` closes the stream it is handed on its success
            // path only; `use` releases it here too when a bank throws before that —
            // the old shape leaked the content stream on every failed/aborted import.
            Result.success(
                inputStream.use {
                    importZipStream(
                        BufferedInputStream(it),
                        fileName.removeSuffix(".zip"),
                        onProgress,
                        builtIn = false,
                        catalogId = catalogId,
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            Result.failure(e)
        }
    }

    /**
     * Import a dictionary bundled in the APK's assets (#43) — the vendored
     * pitch dictionary, which needs no network and no file picker.
     *
     * Re-importing replaces any existing copy with the same title, so the
     * action is idempotent: tapping it twice leaves one dictionary, not two
     * stacked copies of the same 124k rows.
     */
    suspend fun importBundledAsset(
        assetPath: String,
        onProgress: (Int) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            context.assets.open(assetPath).use { readZipTitle(it) }?.let { title ->
                replaceExisting(title)
            }
            val inputStream = context.assets.open(assetPath)
            // A6/#86: same shape as [importZip] — release the asset stream on the
            // failure path too, not only at the end of a successful import.
            Result.success(
                inputStream.use {
                    importZipStream(
                        BufferedInputStream(it),
                        assetPath.substringAfterLast('/').removeSuffix(".zip"),
                        onProgress,
                        builtIn = true,
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bundled import failed", e)
            Result.failure(e)
        }
    }

    /**
     * Remove any existing dictionary whose [title] matches, together with its
     * entries and tags, so the import that follows replaces it instead of
     * stacking a duplicate (#43, #71).
     */
    private suspend fun replaceExisting(title: String) {
        val dao = AppDatabase.getDatabase(context).dictionaryDao()
        dao.findDictionaryByName(title)?.let { existing ->
            Log.i(TAG, "Replacing existing '${existing.name}' (id=${existing.id})")
            dao.deleteEntriesForDictionary(existing.id)
            dao.deleteTagsForDictionary(existing.id)
            dao.deleteDictionary(existing.id)
        }
    }

    /** Read one ZIP entry as UTF-8 text for the shared parser. */
    private fun readZipEntry(input: InputStream): String =
        input.readBytes().toString(Charsets.UTF_8)

    /** Title declared by a zip's index.json, or null when unreadable. */
    private fun readZipTitle(input: InputStream): String? {
        return try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "index.json") {
                        val content = readZipEntry(zip)
                        return yomitanParseIndexTitle(content)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read dictionary title", e)
            null
        }
    }

    /**
     * Shared import core: reads a dictionary zip and writes its rows.
     *
     * [catalogId] is stamped on the meta row this import creates (#71
     * follow-up), so a catalog install can be told apart from a file-picker or
     * bundled one and from a variant with an identical upstream title.
     */
    private suspend fun importZipStream(
        bufferedStream: BufferedInputStream,
        fallbackTitle: String,
        onProgress: (Int) -> Unit,
        builtIn: Boolean = false,
        catalogId: String? = null,
    ): Int = coroutineScope {
            val startTime = System.currentTimeMillis()
            val db = AppDatabase.getDatabase(context)
            val dao = db.dictionaryDao()

            val zipInputStream = ZipInputStream(bufferedStream)

            var dictTitle = fallbackTitle
            var dictionaryId: Int? = null
            var totalProcessed = 0

            /**
             * Insert the meta row the first time a bank needs it, then reuse it.
             * The row is created from whatever title is known at that moment
             * (index.json usually comes first) and [DictionaryMeta.name] is
             * corrected below if the title arrives later. `catalogId` is fixed
             * for the whole import.
             */
            suspend fun ensureDictionary(): Int {
                dictionaryId?.let { return it }
                val maxPriority = dao.getMaxPriority() ?: -1
                return dao.insertDictionary(
                    DictionaryMeta(
                        name = dictTitle,
                        priority = maxPriority + 1,
                        catalogId = catalogId,
                    )
                ).toInt().also { dictionaryId = it }
            }
            
            val batchChannel = Channel<List<DictionaryEntry>>(capacity = 10)
            
            val dbJob = launch {
                for (batch in batchChannel) {
                    dao.insertAll(batch)
                    totalProcessed += batch.size
                    onProgress(totalProcessed)
                }
            }

            var entry = zipInputStream.nextEntry
            while (entry != null) {
                when {
                    entry.name == "index.json" -> {
                        val content = readZipEntry(zipInputStream)
                        val newTitle = yomitanParseIndexTitle(content)
                        if (newTitle != null) {
                            dictTitle = newTitle
                            val id = dictionaryId
                            if (id != null) {
                                dao.updateName(id, dictTitle)
                            }
                        }
                    }
                    entry.name.startsWith("term_bank_") && entry.name.endsWith(".json") -> {
                        val id = ensureDictionary()
                        val content = readZipEntry(zipInputStream)
                        processTermBank(content, id, batchChannel)
                    }
                    entry.name.startsWith("kanji_bank_") && entry.name.endsWith(".json") -> {
                        val id = ensureDictionary()
                        val content = readZipEntry(zipInputStream)
                        processKanjiBank(content, id, batchChannel)
                    }
                    entry.name.startsWith("tag_bank_") && entry.name.endsWith(".json") -> {
                        val id = ensureDictionary()
                        val content = readZipEntry(zipInputStream)
                        parseTagBank(content, dao, id)
                    }
                    // #43: term-meta banks carry pitch-accent data (and freq,
                    // which we skip). Stored like a term entry so lookup finds
                    // it, then filtered out of the entry list at render time.
                    entry.name.startsWith("term_meta_bank_") && entry.name.endsWith(".json") -> {
                        val id = ensureDictionary()
                        val content = readZipEntry(zipInputStream)
                        processTermMetaBank(content, id, batchChannel)
                    }
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
            
            batchChannel.close()
            dbJob.join()
            zipInputStream.close()
            
            // #43: `builtIn` is the completion marker, not a label. Flipping it
            // only after every bank is written means an import killed part-way
            // leaves a non-built-in row, so the next launch imports again
            // instead of trusting a half-present dictionary.
            if (builtIn) dictionaryId?.let { dao.updateBuiltIn(it, true) }

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Imported $totalProcessed entries in ${duration}ms")

            totalProcessed
    }

    private suspend fun processTermBank(
        json: String,
        dictionaryId: Int,
        channel: Channel<List<DictionaryEntry>>,
    ) {
        val entries = yomitanParseTermBank(json).map { row ->
            row.toEntity().copy(dictionaryId = dictionaryId)
        }
        sendEntryBatches(entries, channel)
    }

    private suspend fun processKanjiBank(
        json: String,
        dictionaryId: Int,
        channel: Channel<List<DictionaryEntry>>,
    ) {
        val entries = yomitanParseKanjiBank(json).map { row ->
            row.toEntity().copy(dictionaryId = dictionaryId)
        }
        sendEntryBatches(entries, channel)
    }

    /** Keep Room writes batched after the shared parser has done the pure work. */
    private suspend fun sendEntryBatches(
        entries: List<DictionaryEntry>,
        channel: Channel<List<DictionaryEntry>>,
    ) {
        val batchSize = 5000
        for (batch in entries.chunked(batchSize)) {
            channel.send(batch)
        }
    }

    private suspend fun parseTagBank(json: String, dao: DictionaryDao, dictionaryId: Int) {
        val tags = yomitanParseTagBank(json).map { row ->
            row.toEntity().copy(dictionaryId = dictionaryId)
        }
        if (tags.isNotEmpty()) {
            dao.insertTags(tags)
        }
    }

    /**
     * #43: Yomitan term-meta bank rows are `[term, type, data]`. The shared
     * parser keeps only pitch rows and the Kotlin side still owns batching and
     * the Room write.
     */
    private suspend fun processTermMetaBank(
        json: String,
        dictionaryId: Int,
        channel: Channel<List<DictionaryEntry>>,
    ) {
        val entries = yomitanParseTermMetaBank(json).map { row ->
            row.toEntity().copy(dictionaryId = dictionaryId)
        }
        sendEntryBatches(entries, channel)
    }

}
