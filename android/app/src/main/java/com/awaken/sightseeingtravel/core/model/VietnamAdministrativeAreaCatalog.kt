package com.awaken.sightseeingtravel.core.model

import android.content.Context

/**
 * Administrative areas from Decision 19/2025/QD-TTg, effective July 1, 2025.
 * Keep the bundled TSV in sync with the official national catalog when it changes.
 */
data class VietnamAdministrativeArea(
    val code: String,
    val province: String,
    val commune: String
)

object VietnamAdministrativeAreaCatalog {
    private const val ASSET_FILE = "vietnam_administrative_areas_2025.tsv"

    @Volatile
    private var cachedAreas: List<VietnamAdministrativeArea>? = null

    fun load(context: Context): List<VietnamAdministrativeArea> {
        cachedAreas?.let { return it }

        return synchronized(this) {
            cachedAreas ?: context.applicationContext.assets.open(ASSET_FILE)
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    lines.drop(1).mapNotNull(::parseLine).toList()
                }
                .also { cachedAreas = it }
        }
    }

    private fun parseLine(line: String): VietnamAdministrativeArea? {
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) return null

        val (code, province, commune) = parts
        if (code.isBlank() || province.isBlank() || commune.isBlank()) return null
        return VietnamAdministrativeArea(code, province, commune)
    }
}
