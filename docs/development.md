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
| `LedgerTest` | records on the real database: defaults, balances, multi-currency, transfers, idempotent save, edit without change, delete + undo, tags, archiving, foreign keys, search and combined filters, order of same-minute records, repeat, refund, reconcile, seed, reopening the database |
| `BackupTest` | export → restore → export is identical; bare JSON; archive layout and checksums; exact amount strings; CSV quoting and formula guard; the AI package; damaged, foreign, newer, partial and inconsistent files refused with nothing changed; 50 000 operations |
| `ReceiptTest` | real JPEG compression and all EXIF orientations; originals; atomic save/delete/undo; duplicates and limits; exact photo round-trips and safety copies; missing/damaged/oversized/unsafe archives; staging-loss rollback; v1 JSON/ZIP compatibility |
| `ReceiptFlowTest` | gallery and camera output, duplicate selection, viewer, removal/undo, pending changes and abandonment; optional screenshots via `-Pscreenshots=../docs/screenshots` |
| `AiReaderTest` | the DeepSeek request (forced function, what is sent and what is not), parsing, invented amounts flagged, errors told apart, the key never echoed |
| `MigrationTest` | a database built from the exported v1 schema opens with today's app |
| `JournalFlowTest` | the journal screen end to end: type, see the words, tap one to change it, record, undo; no amount; several operations |
| `AiFlowTest` | DeepSeek off: nothing leaves; on: the unread line goes there, the answer waits for "record" and flags an amount not in the text |

## Layout

```text
android/
  core-ui/   design system: colours, Inter, thin-stroke icons, rows, tokens, the input bar
  data/      everything that is not a screen
    domain/    Money, Currency, Transaction & co, Rules (validation), Draft + DraftBuilder, Balances
    parse/     QuickLine: the deterministic line parser, and its vocabulary
    db/        Room entities, DAO, the filtered query builder, mappers; schemas/ per version (v1 → v2 migration)
    receipts/  bounded JPEG compression, EXIF orientation, metadata and pending photo drafts
    repo/      Ledger (the one place records are written), the starter categories
    backup/    the data format, Codec (write + strict read), CSV, ZIP + manifest, Backups (export/restore)
    ai/        AiReader: DeepSeek, optional
    settings/  choices (prefs) and the encrypted DeepSeek key
  app/       Activity, navigation, view models and screens; lock and GitHub updates
docs/
  export/    format.md, schema.json, the AI instructions and request: packed into the app as assets and into every export
```

## Engineering decisions

See [decisions.md](decisions.md).
