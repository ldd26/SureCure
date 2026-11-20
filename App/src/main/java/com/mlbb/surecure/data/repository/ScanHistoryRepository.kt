
package com.mlbb.surecure.data.repository

import android.content.Context
import com.mlbb.surecure.R
import com.mlbb.surecure.ui.dashboard.ScanHistoryEntry
import com.mlbb.surecure.ui.dashboard.ScanSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class ScanHistoryRepository(context: Context) {

    private val appContext = context.applicationContext

    private val historyFile: File
        get() = File(appContext.filesDir, "scan_history.json")

    @Volatile
    private var cachedEntries: List<ScanHistoryEntry>? = null

    /**
     * Returns cached entries by default.
     * @param forceRefresh Set to true to reread the JSON file.
     */
    suspend fun loadEntries(
        forceRefresh: Boolean = false
    ): List<ScanHistoryEntry> = withContext(Dispatchers.IO) {

        if (!forceRefresh) {
            cachedEntries?.let { return@withContext it }
        }

        if (!historyFile.exists()) {
            historyFile.parentFile?.mkdirs()
            historyFile.writeText("[]")
        }

        val jsonArray = JSONArray(historyFile.readText())
        val entries = mutableListOf<ScanHistoryEntry>()

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)

            val thumbnailName =
                item.optString("thumbnailName", "test_thumbnail")

            // Workaround to get the resourceId of the thumbnail
            val resourceId = appContext.resources.getIdentifier(
                thumbnailName,
                "drawable",
                appContext.packageName
            )

            entries.add(
                ScanHistoryEntry(
                    id = item.getString("id"),
                    safety = ScanSafety.fromStored(
                        item.getString("safety"),
                        item.optString("scanDetails")
                    ),
                    scannedOn = Instant.parse(
                        item.getString("scannedOn")
                    ),
                    thumbnailResId = if (resourceId != 0) {
                        resourceId
                    } else {
                        R.drawable.test_thumbnail
                    },
                    scanDetails = item.optString("scanDetails", "")
                )
            )
        }

        val result = entries.toList()
        cachedEntries = result
        result
    }

    /**
     * Adds a new entry or replaces an entry with the same ID.
     * Writes the file and updates the cache.
     */
    suspend fun saveEntry(
        entry: ScanHistoryEntry
    ) = withContext(Dispatchers.IO) {

        val updated = loadEntries(forceRefresh = true)
            .toMutableList()

        val existingIndex = updated.indexOfFirst {
            it.id == entry.id
        }

        if (existingIndex >= 0) {
            updated[existingIndex] = entry
        } else {
            updated.add(entry)
        }

        val jsonArray = JSONArray()

        updated.forEach { scan ->
            val thumbnailName = runCatching {
                appContext.resources.getResourceEntryName(
                    scan.thumbnailResId
                )
            }.getOrDefault("test_thumbnail")

            jsonArray.put(
                JSONObject().apply {
                    put("id", scan.id)
                    put("safety", scan.safety.name)
                    put("scannedOn", scan.scannedOn.toString())
                    put("thumbnailName", thumbnailName)
                    put("scanDetails", scan.scanDetails)
                }
            )
        }

        historyFile.writeText(jsonArray.toString(2))
        cachedEntries = updated.toList()
    }
}
