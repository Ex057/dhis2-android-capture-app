package org.dhis2.usescases.enrollment

import android.content.Context
import android.util.JsonReader
import org.dhis2.R
import org.dhis2.commons.prefs.PreferenceProvider
import org.dhis2.form.ui.provider.AutoCompleteProvider
import timber.log.Timber

class VillageAutoCompleteProvider(
    private val context: Context,
    private val preferenceProvider: PreferenceProvider,
) : AutoCompleteProvider {

    override fun provideAutoCompleteValues(elementUid: String): List<String>? {
        return if (elementUid == VILLAGE_ATTRIBUTE_UID) {
            Timber.tag(TAG).d("provideAutoCompleteValues called for village TEA: %s", elementUid)
            villages
        } else {
            preferenceProvider.getList(elementUid, emptyList())
        }
    }

    private val villages: List<String> by lazy { loadVillages() }

    private fun loadVillages(): List<String> {
        return try {
            context.resources.openRawResource(R.raw.villages).bufferedReader().use { reader ->
                JsonReader(reader).use { jsonReader ->
                    parseVillages(jsonReader).also {
                        Timber.tag(TAG).d("Loaded %s villages from raw JSON", it.size)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load villages from raw JSON")
            emptyList()
        }
    }

    private fun parseVillages(jsonReader: JsonReader): List<String> {
        val values = mutableListOf<String>()
        jsonReader.beginArray()
        while (jsonReader.hasNext()) {
            val villageValue = parseVillageValue(jsonReader)
            if (villageValue.isNotBlank()) {
                values.add(villageValue)
            }
        }
        jsonReader.endArray()
        return values
    }

    private fun parseVillageValue(jsonReader: JsonReader): String {
        var villageName: String? = null
        var parishName: String? = null
        var subcountyName: String? = null
        var districtName: String? = null

        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            when (jsonReader.nextName()) {
                "village_name" -> villageName = jsonReader.nextString()
                "parish_name" -> parishName = jsonReader.nextString()
                "subcounty_name" -> subcountyName = jsonReader.nextString()
                "District" -> districtName = jsonReader.nextString()
                else -> jsonReader.skipValue()
            }
        }
        jsonReader.endObject()

        return listOfNotNull(villageName, parishName, subcountyName, districtName)
            .filter { it.isNotBlank() }
            .joinToString(VILLAGE_PATH_SEPARATOR)
    }

    companion object {
        private const val TAG = "VillageAutoComplete"
        const val VILLAGE_ATTRIBUTE_UID = "oTI0DLitzFY"
        private const val VILLAGE_PATH_SEPARATOR = " / "
    }
}
