package org.dhis2.form.ui.provider

import android.content.Context
import android.util.JsonReader
import org.dhis2.commons.prefs.PreferenceProvider
import timber.log.Timber

class AutoCompleteProviderImpl(
    private val preferenceProvider: PreferenceProvider,
    private val context: Context? = null,
) : AutoCompleteProvider {
    constructor(preferenceProvider: PreferenceProvider) : this(preferenceProvider, null)

    override fun provideAutoCompleteValues(elementUid: String): List<String>? {
        return if (elementUid in VILLAGE_ATTRIBUTE_UIDS) {
            if (context == null) {
                Timber.tag(TAG).e("Village TEA requested but context is null, fallback to preferences")
                preferenceProvider.getList(elementUid, emptyList())
            } else {
                Timber.tag(TAG).d("provideAutoCompleteValues called for village TEA: %s", elementUid)
                villages(context)
            }
        } else {
            preferenceProvider.getList(elementUid, emptyList())
        }
    }

    private fun villages(context: Context): List<String> {
        return cachedVillages ?: loadVillages(context).also { cachedVillages = it }
    }

    private fun loadVillages(context: Context): List<String> {
        val villagesRawId = context.resources.getIdentifier("villages", "raw", context.packageName)
        if (villagesRawId == 0) {
            Timber.tag(TAG).e("villages.json not found in app/src/main/res/raw")
            return emptyList()
        }

        return try {
            context.resources.openRawResource(villagesRawId).bufferedReader().use { reader ->
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

            val displayValue =
                listOfNotNull(villageName, parishName, subcountyName, districtName)
                    .filter { it.isNotBlank() }
                    .joinToString(VILLAGE_PATH_SEPARATOR)

            if (displayValue.isNotBlank()) {
                values.add(displayValue)
            }
        }
        jsonReader.endArray()
        return values
    }

    companion object {
        private const val TAG = "VillageAutoComplete"
        private val VILLAGE_ATTRIBUTE_UIDS =
            setOf(
                "oTI0DLitzFY",
                "YoteNDkoIwM",
                "pixScollYA6",
            )
        private const val VILLAGE_PATH_SEPARATOR = " / "
        @Volatile
        private var cachedVillages: List<String>? = null
    }
}
