package org.dhis2.form.ui.provider.inputfield

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.dhis2.form.extensions.autocompleteList
import org.dhis2.form.extensions.inputState
import org.dhis2.form.extensions.legend
import org.dhis2.form.extensions.supportingText
import org.dhis2.form.model.FieldUiModel
import org.dhis2.form.model.UiRenderType
import org.dhis2.form.ui.event.RecyclerViewUiEvents
import org.dhis2.form.ui.intent.FormIntent
import org.dhis2.form.ui.provider.onFieldFocusChanged
import org.hisp.dhis.mobile.ui.designsystem.component.InputBarCode
import org.hisp.dhis.mobile.ui.designsystem.component.InputQRCode
import org.hisp.dhis.mobile.ui.designsystem.component.InputStyle
import org.hisp.dhis.mobile.ui.designsystem.component.InputText
import org.hisp.dhis.android.core.common.ValueType
import timber.log.Timber

private const val CHILD_VILLAGE_ATTRIBUTE_UID = "oTI0DLitzFY"
private const val CHILD_PARISH_ATTRIBUTE_UID = "W87HAtUHJjB"
private const val CHILD_SUBCOUNTY_ATTRIBUTE_UID = "PKuyTiVCR89"
private const val CHILD_DISTRICT_ATTRIBUTE_UID = "XjgpfkoxffK"
private const val FATHER_VILLAGE_ATTRIBUTE_UID = "YoteNDkoIwM"
private const val FATHER_PARISH_ATTRIBUTE_UID = "SjvgaRn8m7Y"
private const val FATHER_SUBCOUNTY_ATTRIBUTE_UID = "qbxJxuZCyKu"
private const val FATHER_DISTRICT_ATTRIBUTE_UID = "sOBCVNIm1kX"
private const val MOTHER_VILLAGE_ATTRIBUTE_UID = "pixScollYA6"
private const val MOTHER_PARISH_ATTRIBUTE_UID = "BiergDUeQra"
private const val MOTHER_SUBCOUNTY_ATTRIBUTE_UID = "lqbqW3iYmKl"
private const val MOTHER_DISTRICT_ATTRIBUTE_UID = "lpAaZa1cKCB"
private const val VILLAGE_INPUT_LOG_TAG = "VillageInput"
private const val VILLAGE_MIN_SEARCH_LENGTH = 4
private const val VILLAGE_MAX_SUGGESTIONS = 40
private const val VILLAGE_SEARCH_KEY_SIZE = 4
private const val VILLAGE_SEARCH_DEBOUNCE_MS = 80L

private data class AddressAttributeGroup(
    val villageUid: String,
    val parishUid: String,
    val subCountyUid: String,
    val districtUid: String,
)

private val addressAttributeGroups =
    listOf(
        AddressAttributeGroup(
            villageUid = CHILD_VILLAGE_ATTRIBUTE_UID,
            parishUid = CHILD_PARISH_ATTRIBUTE_UID,
            subCountyUid = CHILD_SUBCOUNTY_ATTRIBUTE_UID,
            districtUid = CHILD_DISTRICT_ATTRIBUTE_UID,
        ),
        AddressAttributeGroup(
            villageUid = FATHER_VILLAGE_ATTRIBUTE_UID,
            parishUid = FATHER_PARISH_ATTRIBUTE_UID,
            subCountyUid = FATHER_SUBCOUNTY_ATTRIBUTE_UID,
            districtUid = FATHER_DISTRICT_ATTRIBUTE_UID,
        ),
        AddressAttributeGroup(
            villageUid = MOTHER_VILLAGE_ATTRIBUTE_UID,
            parishUid = MOTHER_PARISH_ATTRIBUTE_UID,
            subCountyUid = MOTHER_SUBCOUNTY_ATTRIBUTE_UID,
            districtUid = MOTHER_DISTRICT_ATTRIBUTE_UID,
        ),
    )

private val addressAttributeGroupByVillageUid = addressAttributeGroups.associateBy { it.villageUid }

private data class VillageEntry(
    val displayValue: String,
    val normalizedValue: String,
    val normalizedTokens: List<String>,
    val normalizedDistrict: String,
)

private data class VillageSearchIndex(
    val entries: List<VillageEntry>,
    val indexByPrefix: Map<String, IntArray>,
    val indexByDistrictPrefix: Map<String, IntArray>,
) {
    fun search(
        query: String,
        maxResults: Int,
    ): List<String> {
        val normalizedQuery = normalizeForSearch(query)
        if (normalizedQuery.length < VILLAGE_MIN_SEARCH_LENGTH) {
            return emptyList()
        }

        val queryTokens =
            normalizedQuery
                .split(' ')
                .filter { it.isNotBlank() }

        val queryPrefixes =
            queryTokens
                .filter { it.length >= VILLAGE_SEARCH_KEY_SIZE }
                .map { it.take(VILLAGE_SEARCH_KEY_SIZE) }
                .distinct()

        if (queryPrefixes.isEmpty()) {
            return emptyList()
        }

        val districtCandidateSet =
            queryPrefixes
                .asSequence()
                .flatMap { prefix -> indexByDistrictPrefix[prefix]?.asSequence() ?: emptySequence() }
                .toSet()

        var candidateIndexSet: MutableSet<Int>? =
            if (districtCandidateSet.isNotEmpty()) {
                districtCandidateSet.toMutableSet()
            } else {
                null
            }

        if (candidateIndexSet == null) {
            queryPrefixes.forEach { prefix ->
                val bucket = indexByPrefix[prefix]?.toSet() ?: emptySet()
                candidateIndexSet =
                    if (candidateIndexSet == null) {
                        bucket.toMutableSet()
                    } else {
                        candidateIndexSet!!.apply { retainAll(bucket) }
                    }
            }
        }

        val candidateIndexes = candidateIndexSet ?: emptySet()
        if (candidateIndexes.isEmpty()) {
            return emptyList()
        }

        val ranked =
            candidateIndexes
                .asSequence()
                .map { entries[it] }
                .filter { entry ->
                    queryTokens.all { queryToken ->
                        entry.normalizedTokens.any { entryToken ->
                            entryToken.startsWith(queryToken)
                        }
                    }
                }
                .sortedWith(
                    compareBy<VillageEntry> {
                        scoreVillageEntry(
                            it,
                            normalizedQuery,
                            queryTokens,
                            hasDistrictBucket = districtCandidateSet.isNotEmpty(),
                        )
                    }
                        .thenBy { it.displayValue },
                ).take(maxResults)
                .map { it.displayValue }
                .toList()

        return ranked
    }
}

private fun scoreVillageEntry(
    entry: VillageEntry,
    normalizedQuery: String,
    queryTokens: List<String>,
    hasDistrictBucket: Boolean,
): Int {
    val startsWithFull = entry.normalizedValue.startsWith(normalizedQuery)
    val tokenStartsWithFull = entry.normalizedTokens.any { token -> token.startsWith(normalizedQuery) }
    val startsWithAllTokens = queryTokens.all { queryToken -> entry.normalizedTokens.any { it.startsWith(queryToken) } }
    val districtStartsWithFull = entry.normalizedDistrict.startsWith(normalizedQuery)

    return when {
        hasDistrictBucket && districtStartsWithFull -> 0
        startsWithFull -> 0
        tokenStartsWithFull -> 1
        startsWithAllTokens -> 2
        else -> 3
    }
}

private fun splitNormalizedTokens(value: String): List<String> =
    value
        .split(' ')
        .filter { it.isNotBlank() }

private fun buildVillageSearchIndex(source: List<String>): VillageSearchIndex {
    val sortedSource =
        source
            .distinct()
            .sortedBy { normalizeForSearch(it) }

    val entries = mutableListOf<VillageEntry>()
    val buckets = mutableMapOf<String, MutableList<Int>>()
    val districtBuckets = mutableMapOf<String, MutableList<Int>>()

    sortedSource.forEach { displayValue ->
        val normalizedValue = normalizeForSearch(displayValue)
        if (normalizedValue.isBlank()) return@forEach

        val normalizedTokens = splitNormalizedTokens(normalizedValue)
        val normalizedDistrict =
            normalizeForSearch(
                displayValue
                    .split('/')
                    .lastOrNull()
                    ?.trim()
                    .orEmpty(),
            )
        val entryIndex = entries.size
        entries.add(
            VillageEntry(
                displayValue = displayValue,
                normalizedValue = normalizedValue,
                normalizedTokens = normalizedTokens,
                normalizedDistrict = normalizedDistrict,
            ),
        )

        val keys = mutableSetOf<String>()
        if (normalizedValue.length >= VILLAGE_SEARCH_KEY_SIZE) {
            keys.add(normalizedValue.take(VILLAGE_SEARCH_KEY_SIZE))
        }

        normalizedTokens
            .filter { it.length >= VILLAGE_SEARCH_KEY_SIZE }
            .forEach { token ->
                keys.add(token.take(VILLAGE_SEARCH_KEY_SIZE))
            }
        

        keys.forEach { key ->
            buckets.getOrPut(key) { mutableListOf() }.add(entryIndex)
        }

        if (normalizedDistrict.length >= VILLAGE_SEARCH_KEY_SIZE) {
            districtBuckets
                .getOrPut(normalizedDistrict.take(VILLAGE_SEARCH_KEY_SIZE)) { mutableListOf() }
                .add(entryIndex)
        }
    }

    return VillageSearchIndex(
        entries = entries,
        indexByPrefix = buckets.mapValues { (_, indexes) -> indexes.toIntArray() },
        indexByDistrictPrefix = districtBuckets.mapValues { (_, indexes) -> indexes.toIntArray() },
    )
}

private fun normalizeForSearch(value: String): String {
    val normalized = StringBuilder(value.length)
    var previousWasSpace = true

    value.forEach { char ->
        val normalizedChar =
            when {
                char.isLetterOrDigit() -> char.lowercaseChar()
                else -> ' '
            }

        if (normalizedChar == ' ') {
            if (!previousWasSpace) {
                normalized.append(' ')
                previousWasSpace = true
            }
        } else {
            normalized.append(normalizedChar)
            previousWasSpace = false
        }
    }

    return normalized.toString().trim()
}

private data class VillageParts(
    val village: String,
    val parish: String?,
    val subCounty: String?,
    val district: String?,
)

private fun parseVillageParts(displayValue: String): VillageParts {
    val parts = displayValue.split(" / ").map { it.trim() }
    return VillageParts(
        village = parts.getOrNull(0).orEmpty(),
        parish = parts.getOrNull(1),
        subCounty = parts.getOrNull(2),
        district = parts.getOrNull(3),
    )
}

@Composable
internal fun ProvideInputsForValueTypeText(
    modifier: Modifier = Modifier,
    inputStyle: InputStyle,
    fieldUiModel: FieldUiModel,
    intentHandler: (FormIntent) -> Unit,
    uiEventHandler: (RecyclerViewUiEvents) -> Unit,
    focusManager: FocusManager,
    onNextClicked: () -> Unit,
) {
    when (fieldUiModel.renderingType) {
        UiRenderType.QR_CODE, UiRenderType.GS1_DATAMATRIX -> {
            ProvideQRInput(
                modifier = modifier,
                inputStyle = inputStyle,
                fieldUiModel = fieldUiModel,
                intentHandler = intentHandler,
                uiEventHandler = uiEventHandler,
                focusManager = focusManager,
                onNextClicked = onNextClicked,
            )
        }

        UiRenderType.BAR_CODE -> {
            ProvideBarcodeInput(
                modifier = modifier,
                inputStyle = inputStyle,
                fieldUiModel = fieldUiModel,
                intentHandler = intentHandler,
                uiEventHandler = uiEventHandler,
                focusManager = focusManager,
                onNextClicked = onNextClicked,
            )
        } else -> {
            ProvideDefaultTextInput(
                modifier = modifier,
                inputStyle = inputStyle,
                fieldUiModel = fieldUiModel,
                intentHandler = intentHandler,
                focusManager = focusManager,
                onNextClicked = onNextClicked,
            )
        }
    }
}

@Composable
private fun ProvideQRInput(
    modifier: Modifier,
    inputStyle: InputStyle,
    fieldUiModel: FieldUiModel,
    intentHandler: (FormIntent) -> Unit,
    uiEventHandler: (RecyclerViewUiEvents) -> Unit,
    focusManager: FocusManager,
    onNextClicked: () -> Unit,
) {
    val textSelection = TextRange(if (fieldUiModel.value != null) fieldUiModel.value!!.length else 0)
    var value by remember(fieldUiModel.value) {
        mutableStateOf(TextFieldValue(fieldUiModel.value ?: "", textSelection))
    }

    var clickedOnNext by remember {
        mutableStateOf(false)
    }

    var isFocused by remember {
        mutableStateOf(false)
    }

    var lostFocus by remember {
        mutableStateOf(false)
    }

    InputQRCode(
        modifier = modifier.fillMaxWidth(),
        title = fieldUiModel.label,
        state = fieldUiModel.inputState(),
        supportingText = fieldUiModel.supportingText(),
        legendData = fieldUiModel.legend(),
        inputTextFieldValue = value,
        inputStyle = inputStyle,
        isRequiredField = fieldUiModel.mandatory,
        onNextClicked = {
            clickedOnNext = true
            onNextClicked()
        },
        onValueChanged = {
            value = it ?: TextFieldValue()
            intentHandler(
                FormIntent.OnTextChange(
                    fieldUiModel.uid,
                    value.text,
                    fieldUiModel.valueType,
                ),
            )
        },
        onQRButtonClicked = {
            if (value.text.isEmpty()) {
                uiEventHandler.invoke(
                    RecyclerViewUiEvents.ScanQRCode(
                        fieldUiModel.uid,
                        optionSet = fieldUiModel.optionSet,
                        fieldUiModel.renderingType,
                    ),
                )
            } else {
                uiEventHandler.invoke(
                    RecyclerViewUiEvents.DisplayQRCode(
                        fieldUiModel.uid,
                        optionSet = fieldUiModel.optionSet,
                        value = value.text,
                        renderingType = fieldUiModel.renderingType,
                        editable = fieldUiModel.editable,
                        label = fieldUiModel.label,
                    ),
                )
            }
        },
        onFocusChanged = { isFocused ->
            lostFocus = lostFocus == true && isFocused == false
            onFieldFocusChanged(
                fieldUid = fieldUiModel.uid,
                value = value.text,
                valueType = fieldUiModel.valueType,
                lostFocus = lostFocus,
                onNextClicked = clickedOnNext,
                intentHandler = intentHandler,
            )
        },
        autoCompleteList = fieldUiModel.autocompleteList(),
        autoCompleteItemSelected = {
            focusManager.clearFocus()
        },
    )
}

@Composable
private fun ProvideDefaultTextInput(
    modifier: Modifier,
    inputStyle: InputStyle = InputStyle.DataInputStyle(),
    fieldUiModel: FieldUiModel,
    intentHandler: (FormIntent) -> Unit,
    focusManager: FocusManager,
    onNextClicked: () -> Unit,
) {
    val textSelection = TextRange(fieldUiModel.value?.length ?: 0)
    var value by remember(fieldUiModel.value) {
        mutableStateOf(TextFieldValue(fieldUiModel.value ?: "", textSelection))
    }

    var clickedOnNext by remember {
        mutableStateOf(false)
    }

    var fieldIsFocused by remember {
        mutableStateOf(false)
    }

    var lostFocus by remember {
        mutableStateOf(false)
    }

    var villageSearchIndex by remember(fieldUiModel.uid, fieldUiModel.autocompleteList()) {
        mutableStateOf<VillageSearchIndex?>(null)
    }

    LaunchedEffect(fieldUiModel.uid, fieldUiModel.autocompleteList()) {
        if (addressAttributeGroupByVillageUid.containsKey(fieldUiModel.uid)) {
            villageSearchIndex =
                withContext(Dispatchers.Default) {
                    buildVillageSearchIndex(fieldUiModel.autocompleteList().orEmpty())
                }
        } else {
            villageSearchIndex = null
        }
    }

    var autoCompleteOptions by remember(fieldUiModel.uid) {
        mutableStateOf<List<String>?>(if (addressAttributeGroupByVillageUid.containsKey(fieldUiModel.uid)) emptyList() else fieldUiModel.autocompleteList())
    }

    LaunchedEffect(fieldUiModel.uid, value.text, villageSearchIndex, fieldUiModel.autocompleteList()) {
        if (!addressAttributeGroupByVillageUid.containsKey(fieldUiModel.uid)) {
            autoCompleteOptions = fieldUiModel.autocompleteList()
            return@LaunchedEffect
        }

        val query = value.text
        if (query.length < VILLAGE_MIN_SEARCH_LENGTH) {
            autoCompleteOptions = emptyList()
            Timber.tag(VILLAGE_INPUT_LOG_TAG).d(
                "Village search updated: query='%s', shownSize=%s",
                query,
                0,
            )
            return@LaunchedEffect
        }

        delay(VILLAGE_SEARCH_DEBOUNCE_MS)
        val searchedOptions =
            withContext(Dispatchers.Default) {
                villageSearchIndex?.search(query, VILLAGE_MAX_SUGGESTIONS) ?: emptyList()
            }
        autoCompleteOptions = searchedOptions
        Timber.tag(VILLAGE_INPUT_LOG_TAG).d(
            "Village search updated: query='%s', shownSize=%s",
            query,
            searchedOptions.size,
        )
    }

    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        InputText(
            modifier = Modifier.fillMaxWidth(),
            title = fieldUiModel.label,
            state = fieldUiModel.inputState(),
            supportingText = fieldUiModel.supportingText(),
            legendData = fieldUiModel.legend(),
            inputTextFieldValue = value,
            inputStyle = inputStyle,
            isRequiredField = fieldUiModel.mandatory,
            onNextClicked = {
                clickedOnNext = true
                onNextClicked()
            },
            onValueChanged = {
                value = it ?: TextFieldValue()
                if (addressAttributeGroupByVillageUid.containsKey(fieldUiModel.uid)) {
                    Timber.tag(VILLAGE_INPUT_LOG_TAG).d(
                        "Village TEA typing: uid=%s, text='%s', renderType=%s, sourceSize=%s, shownSize=%s, bucket=%s",
                        fieldUiModel.uid,
                        value.text,
                        fieldUiModel.renderingType,
                        fieldUiModel.autocompleteList()?.size ?: 0,
                        autoCompleteOptions?.size ?: 0,
                        normalizeForSearch(value.text).take(VILLAGE_SEARCH_KEY_SIZE),
                    )
                }
                intentHandler(
                    FormIntent.OnTextChange(
                        fieldUiModel.uid,
                        value.text,
                        fieldUiModel.valueType,
                    ),
                )
            },
            onFocusChanged = { isFocused ->
                fieldIsFocused = isFocused
                lostFocus = lostFocus == true && !isFocused
                onFieldFocusChanged(
                    fieldUid = fieldUiModel.uid,
                    value = value.text,
                    valueType = fieldUiModel.valueType,
                    lostFocus = lostFocus,
                    onNextClicked = clickedOnNext,
                    intentHandler = intentHandler,
                )
            },
            autoCompleteList = if (addressAttributeGroupByVillageUid.containsKey(fieldUiModel.uid)) emptyList() else autoCompleteOptions,
            onAutoCompleteItemSelected = {
                focusManager.clearFocus()
            },
        )

        if (addressAttributeGroupByVillageUid.containsKey(fieldUiModel.uid) &&
            fieldIsFocused &&
            value.text.length >= VILLAGE_MIN_SEARCH_LENGTH &&
            !autoCompleteOptions.isNullOrEmpty()
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(Color.White),
            ) {
                items(items = autoCompleteOptions ?: emptyList()) { option ->
                    Text(
                        text = option,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val addressAttributeGroup = addressAttributeGroupByVillageUid[fieldUiModel.uid] ?: return@clickable
                                    val villageParts = parseVillageParts(option)
                                    val villageValue = villageParts.village
                                    value = TextFieldValue(villageValue, TextRange(villageValue.length))
                                    intentHandler(
                                        FormIntent.OnSave(
                                            fieldUiModel.uid,
                                            villageValue,
                                            ValueType.TEXT,
                                        ),
                                    )
                                    villageParts.parish?.let { parish ->
                                        intentHandler(
                                            FormIntent.OnSave(
                                                addressAttributeGroup.parishUid,
                                                parish,
                                                ValueType.TEXT,
                                            ),
                                        )
                                    }
                                    villageParts.subCounty?.let { subCounty ->
                                        intentHandler(
                                            FormIntent.OnSave(
                                                addressAttributeGroup.subCountyUid,
                                                subCounty,
                                                ValueType.TEXT,
                                            ),
                                        )
                                    }
                                    villageParts.district?.let { district ->
                                        intentHandler(
                                            FormIntent.OnSave(
                                                addressAttributeGroup.districtUid,
                                                district,
                                                ValueType.TEXT,
                                            ),
                                        )
                                    }
                                    autoCompleteOptions = emptyList()
                                    focusManager.clearFocus()
                                }.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProvideBarcodeInput(
    modifier: Modifier,
    inputStyle: InputStyle,
    fieldUiModel: FieldUiModel,
    intentHandler: (FormIntent) -> Unit,
    uiEventHandler: (RecyclerViewUiEvents) -> Unit,
    focusManager: FocusManager,
    onNextClicked: () -> Unit,
) {
    val textSelection = TextRange(if (fieldUiModel.value != null) fieldUiModel.value!!.length else 0)

    var value by remember(fieldUiModel.value) {
        mutableStateOf(TextFieldValue(fieldUiModel.value ?: "", textSelection))
    }

    var clickedOnNext by remember {
        mutableStateOf(false)
    }

    var lostFocus by remember {
        mutableStateOf(false)
    }

    InputBarCode(
        modifier = modifier.fillMaxWidth(),
        inputStyle = inputStyle,
        title = fieldUiModel.label,
        state = fieldUiModel.inputState(),
        supportingText = fieldUiModel.supportingText(),
        legendData = fieldUiModel.legend(),
        inputTextFieldValue = value,
        isRequiredField = fieldUiModel.mandatory,
        onNextClicked = {
            clickedOnNext = true
            onNextClicked()
        },
        onValueChanged = {
            value = it ?: TextFieldValue()
            intentHandler(
                FormIntent.OnTextChange(
                    fieldUiModel.uid,
                    value.text,
                    fieldUiModel.valueType,
                ),
            )
        },
        onActionButtonClicked = {
            if (value.text.isEmpty()) {
                uiEventHandler.invoke(
                    RecyclerViewUiEvents.ScanQRCode(
                        fieldUiModel.uid,
                        optionSet = fieldUiModel.optionSet,
                        fieldUiModel.renderingType,
                    ),
                )
            } else {
                uiEventHandler.invoke(
                    RecyclerViewUiEvents.DisplayQRCode(
                        fieldUiModel.uid,
                        optionSet = fieldUiModel.optionSet,
                        value = value.text,
                        renderingType = fieldUiModel.renderingType,
                        editable = fieldUiModel.editable,
                        label = fieldUiModel.label,
                    ),
                )
            }
        },
        onFocusChanged = { isFocused ->
            lostFocus = lostFocus == true && isFocused == false
            onFieldFocusChanged(
                fieldUid = fieldUiModel.uid,
                value = value.text,
                valueType = fieldUiModel.valueType,
                lostFocus = lostFocus,
                onNextClicked = clickedOnNext,
                intentHandler = intentHandler,
            )
        },
        autoCompleteList = fieldUiModel.autocompleteList(),
        autoCompleteItemSelected = {
            focusManager.clearFocus()
        },
    )
}
