# SpendCodex: development notes

## Build and run

JDK 17+ (21 works) and the Android SDK (compileSdk 37). `android/local.properties` points at the SDK (`sdk.dir=…`).

```bash
cd android
./gradlew assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                    # R8-shrunk, ~2.5 MB, signed with the debug key unless keystore.properties exists
adb install -r app/build/outputs/apk/release/app-release.apk
```

- Own signing key: `android/keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword` (ignored by git).
  GitHub updates install only over an app signed with the same key.
- DeepSeek against a stand-in: `./gradlew assembleDebug -PmodelBaseUrl=http://10.0.2.2:8090`.
- Text shared from another app ("Share" → SpendCodex) lands in the quick line. Handy for typing Cyrillic on an emulator:
  `adb shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'кофе 4.50' -n dev.spendcodex/.MainActivity"`.
- Hardware keyboard: Enter records, Shift+Enter starts another operation, Ctrl+N opens the full form, Ctrl+F the search,
  Ctrl+S saves the form, Esc goes back.

## Tests

```bash
cd android
./gradlew :data:testDebugUnitTest :app:testDebugUnitTest
```

JVM only (Robolectric with real SQLite), no emulator needed. What they cover:

| Suite | What |
|---|---|
| `MoneyTest` | exact decimals, currency exponents, refusal instead of rounding, formats |
| `QuickLineTest` | the line grammar in Russian and English: amounts, currencies, dates, accounts, transfers, refunds, tags, notes, splitting, ambiguities |
| `LedgerTest` | records on the real database: defaults, balances, multi-currency, transfers, idempotent save, edit without change, delete + undo, tags, archiving, foreign keys, search and combined filters (account names not searched), order of same-minute records, repeat, refund, reconcile, seed with one hidden account, a record always finding an account, reopening the database |
| `BackupTest` | export → restore → export is identical; bare JSON; archive layout and checksums; exact amount strings; CSV quoting and formula guard; the AI package; damaged, foreign, newer, partial and inconsistent files refused with nothing changed; 50 000 operations |
| `ReceiptTest` | real JPEG compression and all EXIF orientations; originals; atomic save/delete/undo; duplicates and limits; exact photo round-trips and safety copies; missing/damaged/oversized/unsafe archives; staging-loss rollback; v1 JSON/ZIP compatibility |
| `ReceiptFlowTest` | gallery and camera output, duplicate selection, viewer, removal/undo, pending changes and abandonment; optional screenshots via `-Pscreenshots=../docs/screenshots` |
| `AiReaderTest` | the forced DeepSeek tool, expense-only schema, generated tags and vocabulary, exact amounts, malformed batches, truncation, invalid fields, errors and redacted keys |
| `PaletteTest` | every palette (black & white, avocado; light and dark) keeps text at WCAG 4.5:1; the black & white look is unchanged; the choice persists and survives erasing |
| `MigrationTest` | a database built from the exported v1 schema opens with today's app |
| `JournalFlowTest` | secondary manual entry, expense-only feed/totals, legacy data preserved and editable, old income searchable explicitly |
| `AiFlowTest` | primary DeepSeek input, automatic save and tags, batch atomicity, currencies, undo, no-key state, income rejection, network retry, cancellation and duplicate taps |

## Layout

```text
android/
  core-ui/   design system: colours, Inter, thin-stroke icons, rows, tokens, the input bar
  data/      everything that is not a screen
    domain/    Money, Currency, Transaction & co, Rules (validation), Draft + DraftBuilder, Balances
    parse/     legacy QuickLine grammar; only number tokenisation is used to verify AI amounts
    db/        Room entities, DAO, the filtered query builder, mappers; schemas/ per version (v1 → v2 migration)
    receipts/  bounded JPEG compression, EXIF orientation, metadata and pending photo drafts
    repo/      Ledger (the one place records are written), the starter categories
    backup/    the data format, Codec (write + strict read), CSV, ZIP + manifest, Backups (export/restore)
    ai/        AiReader: primary DeepSeek expense extraction and tagging
    settings/  choices (prefs) and the encrypted DeepSeek key
  app/       Activity, navigation, view models and screens; lock and GitHub updates
docs/
  export/    format.md, schema.json, the AI instructions and request: packed into the app as assets and into every export
```

## Engineering decisions

See [decisions.md](decisions.md).

## DeepSeek input contract (0.3)

Every explicit send uses `deepseek-flash` at `/chat/completions`, with thinking disabled and the forced
`record_operations` tool. The model name and endpoint match the [official API guide](https://api-docs.deepseek.com/).
The key is provided by the user; tests use MockWebServer, never a paid account. The original typed note is retained in
`input_text`, with `origin=ai`. Categories and generated tags are classifications, not additional financial facts.

No automatic fallback to QuickLine exists. No key/network: keep the note and offer retry, key setup or the manual form.
Ordinary validated expense batches save atomically; unknown references require review; invalid money/date/currency blocks
saving. Amounts absent from the text cannot be accepted through the AI confirmation action. Editing/cancelling input cancels
the HTTP call; stable batch IDs and a synchronous busy guard prevent duplicate saves. Manual entry and stored history work offline.

Room schema and JSON format remain version 2. The old `ai_enabled` preference is round-tripped for compatibility but no longer
gates primary input. Non-expense records and account opening amounts remain intact; only expenses/refunds appear in the main
feed. Legacy CSV balance columns remain present and empty; no balance calculation runs in screens or export.
