# Aggregate Dataset Form Accordion Structure

## Goal
This document defines only the accordion structure and interaction flow for the aggregate dataset form. It does not cover login, metadata sync architecture, tracker flow, or settings behavior.

The target app must reproduce a dataset form that supports:
- section-based navigation
- data-element-first grouping
- nested category-option expansion when a data element has category combos
- offline-first rendering from cached and local draft values

## Reference Behavior
Use this app as the behavioral reference for responsibilities:
- `EditEntryScreen`: assembles and renders the accordion tree
- `SectionAccordion`: section shell container
- `DataEntryViewModel`: owns expansion state, current section index, grouped values, and form readiness
- `MetadataCacheService`: provides ordered sections and optimized dataset metadata
- `DataEntryRepositoryImpl`: merges cached values and drafts before render

The target app does not need to copy class names. It must preserve the same responsibilities.

## Accordion Hierarchy

### 1. Section Level
- Render one top-level accordion shell per dataset section.
- Section title comes from the dataset section name.
- If the dataset has no sections, synthesize one section named `Default Section`.
- Section order must follow dataset section `sortOrder`, with display name as stable fallback.
- Only one section is the active navigation target at a time.

### 2. Data Element Level
- Inside each section, render one accordion per distinct data element.
- Header text is the data element display name.
- Subtitle/progress is `filledElementCount/dataElementCount completed`.
- The header must visually indicate whether any value inside that data element group is filled.
- Expanding a data element reveals one of these shapes:
  - one direct field when there is no category combo
  - a flat list of option rows when there is only one category dimension
  - nested recursive category accordions when there are multiple category dimensions

### 3. Category Level
- Only render this level when the data element has category combo structure.
- For one category dimension:
  - render flat rows directly
  - place the option label on the left and the input on the right
- For multiple category dimensions:
  - render recursive nested accordions
  - each accordion level corresponds to one category in the combo path
  - the leaf node renders one or more input fields mapped to the resolved category option combo

## Screen Flow
The dataset form flow must be:

1. User opens the dataset instance form.
2. App checks form metadata readiness.
3. App loads an optimized dataset metadata package containing:
   - sections
   - section data elements
   - category combo structures
   - category option combos
   - org units
   - cached data values
4. App overlays local drafts on top of cached values.
5. App groups values by:
   - section
   - data element
   - category option combo
6. App renders the current section shell.
7. Inside that section, app renders data-element accordions in stable metadata order.
8. Expanding a data element reveals direct fields or nested category accordions.
9. Field edits update local state immediately and persist as offline draft data.

## State Model
The target app must carry equivalent state for:
- `currentSectionIndex`
- `totalSections`
- `isExpandedSections`
- `expandedAccordions`
- `valuesByElement`
- `valuesByCombo`
- `dataElementsBySection`
- `categoryComboStructures`
- `optionUidsToComboUid`
- `dataElementOrdering`

Behavior rules:
- section navigation state must survive recomposition
- expanded data-element accordion state must be independent from section navigation
- nested category accordion state must be keyed by parent path plus current node key
- field state must be keyed by `dataElement|categoryOptionCombo`
- if data reloads, preserve the current section index when it remains valid

## Rendering Rules
- Section shell must be a card-style accordion with:
  - title
  - optional subtitle
  - expand/collapse icon
  - divider before expanded content
- Data-element accordions are the primary interaction unit inside a section.
- If a data element has no category combo, render a single input field directly.
- If a data element has one category dimension, render compact row-style inputs.
- If a data element has multiple category dimensions, render recursive category accordions until leaf level.
- Labels must truncate safely with ellipsis.
- Empty sections must still render a stable shell and must not disappear or crash.
- Hidden fields must not render.
- Disabled or completed fields must remain visible but non-editable.
- Mandatory fields must visibly indicate required status.

## Ordering Rules
- Order sections by dataset metadata sort order.
- Order data elements by precomputed metadata order per section.
- Order category groups by metadata-provided option order.
- Fallback ordering must be stable alphabetical by display name.
- Recomposition must not reorder visible items.

## Special Cases
- `No dataset sections`
  - synthesize one `Default Section`
- `No category combo`
  - render a direct field under the data-element accordion
- `Single category dimension`
  - render flat compact rows, not another unnecessary nested shell
- `Multiple category dimensions`
  - render a recursive category accordion tree
- `Grid-friendly labels`
  - the target app may optionally compact repeated row or column label patterns, but this is secondary to the accordion structure
- `Completed dataset`
  - keep the accordion browseable but fields read-only
- `Metadata still preparing`
  - show a blocked or preparing state instead of a partial broken accordion

## Data Dependencies
The accordion form depends on these inputs being prepared before render:
- dataset sections with data-element membership
- data element metadata
- category combo structures
- category option combos
- resolved mapping from option UID sets to category option combo UID
- locally available data values
- local drafts

The target app should precompute and cache these structures before first render where possible.

## Navigation Contract
- Provide previous and next section navigation.
- Show a current section indicator as `index / total`.
- Selecting a subsection or field may move visible section focus when needed.
- Accordion expansion must not reset the section index.
- Section navigation must not collapse unrelated saved field values.

## Save and Edit Contract
- Field edits update in-memory state immediately.
- Edits must be written to local draft storage or offline queue without waiting for server sync.
- Reopening the form must restore drafts into the accordion structure.
- Sync is separate from form rendering and must not rebuild accordion state from scratch unless metadata changed.

## Acceptance Tests
- dataset with explicit sections renders one accordion shell per section in metadata order
- dataset with no sections renders one synthetic `Default Section`
- section opens and shows data-element accordions in stable order
- data element with no category combo renders one direct field
- data element with one category dimension renders flat labeled rows
- data element with multiple category dimensions renders nested recursive accordions
- expanding one data-element accordion does not corrupt another section’s state
- field values reload correctly from cached values plus local drafts
- completed dataset remains readable and non-editable
- returning to the form preserves section position and expansion state when feasible

## Defaults and Assumptions
- This document is only about the dataset form accordion structure.
- It intentionally excludes login, metadata sync orchestration, tracker flow, and settings flow.
- The target app should mimic behavior, grouping, and interaction shape from this app, not copy class names.
- Offline-first cached rendering is required because the accordion depends on locally available grouped values.
