# Decisions

Short and concrete: what was chosen and why. The brief is in [brief.ru.md](brief.ru.md); where the person's answers to the
clarifying questions differ from it, the answers win (they are noted below).

## Product

- **Android, same stack as FatCodex** (Kotlin, Compose, Room, Hilt; modules `app`, `core-ui`, `data`). Chosen by the person
  over a web/PWA client. The brief's mentions of hotkeys are covered for hardware keyboards; there is no command palette.
- **One screen to write, like FatCodex.** The journal is the main page; the line at the bottom is the input. What FatCodex
  does with a model (read a sentence into records), SpendCodex does with a **deterministic local parser**, and shows its
  reading word by word *before* recording: the "preview for confirmation" of the brief is always on, not only for doubtful
  lines. A full form exists for everything the line cannot say.
- **DeepSeek is optional** (the person's choice, against the brief's "no AI inside"). It is off by default, needs the person's
  own key, and is only used for a line the parser could not read. It sends that line, today's date and the names of accounts and
  categories, never amounts or history. It fills in fields, nothing else: there is no chat, no advice, no analysis. Every amount
  it returns is checked against the numbers actually written in the line.
- **Carried over from FatCodex:** RU/EN switch, biometric lock (same logic), GitHub updates (same verification of size,
  SHA-256 and signing key), "erase everything" with two taps, the export-for-analysis idea, the visual language.
  Not carried over: voice, camera, server mode, the food database.
- **Navigation:** the journal; ⋮ → settings (Data first, then Accounts, Categories and tags, choices); a magnifier → search.
  The brief's five sections are there, arranged the FatCodex way (one main page, everything else one tap behind it).

## Money

- Amounts are `Long` minor units with the currency's exponent from a fixed ISO 4217 table (`Currencies`); the table goes into
  every export. No floating point anywhere. Parsing refuses more decimals than the currency has instead of rounding.
  Maximum amount 10^15 minor units: sums cannot overflow, and the values are exact even as JSON doubles.
- Each record keeps what was written (`amount` + `currency`) and, when that currency is not the account's, what the account
  moved (`account_amount`). A transfer leaves in its account's currency and arrives as `to_amount` when the currencies differ
  (then required). There are no exchange rates in the app: an amount that cannot be counted makes the balance visibly
  incomplete.
- Types: expense, income, transfer, refund (linked to an expense when known), adjustment (signed). The balance formula is one
  pure function (`Balances`) and is written down in the format description.

## Time

- `date` is the person's day, `time` the minute or **null when unknown** (written for "yesterday" → no time), `time_zone` the
  phone's zone when recorded. `created_at`/`updated_at` are the record's own times, UTC ms.
- Order: date, time (unknown last in its day), `created_at`, `id`: stable for records at the same minute.
- A day and month without a year is the latest such day not in the future, and the line says so when that is last year.

## Storage

- Room with foreign keys on (NO ACTION; SET NULL for a refund's link): an account, category or tag in use cannot be deleted,
  only archived; archived ones stay valid on old records.
- Records are inserted with IGNORE on their id: a draft keeps its id until recorded, so a second tap stores nothing new.
  Updates are upserts, never REPLACE (which would delete the row and cascade). An edit that changes nothing is not written.
- Search: normalised text (lowercase, ё→е) kept beside names and texts, LIKE with escaped wildcards, every word must match
  somewhere (text, amount, account, category, tag); all filters are bound arguments. The journal loads 200 records at a time.
  Measured on the JVM with 50 000 records: first page 122 ms, a search 59 ms, balances 80 ms.
- Settings are SharedPreferences (`app`); the DeepSeek key is AES-GCM with a Keystore key in `secrets`, excluded from
  Android's backup and from every export.

## Export and restore

- Format version 2 (`docs/export/format.md`, `schema.json`; v1 remains readable): one JSON with every entity, ids and links; amounts as strings;
  sorted lists, so two exports of the same data are identical but for `exported_at`.
- The full backup is a ZIP with the JSON, CSV tables, the description and the schema, and a manifest with SHA-256 per file.
  CSV is RFC 4180, UTF-8 without BOM (as in FatCodex), with a formula guard on person-written text only.
- Restore = full replacement, the only mode the brief requires and the only one whose correctness can be guaranteed. The file
  is read and checked completely first (format, version, every field, every reference, every rule of the journal); a partial
  export is refused; the current journal is written to a safety copy; then everything is replaced in one transaction.
- Schema migrations: Room schemas are exported per version (`data/schemas`), `Migrations.ALL` holds the steps, and
  `MigrationTest` opens a database built from an old schema. The file format has its own `format_version`: an older app refuses
  a newer file instead of guessing.

## What is deliberately not there

Charts, dashboards, budgets, forecasts, advice, sync, user accounts, bank integrations and exchange rates.

## Receipt photos

- The person's clarification: both camera and gallery, local compression for readable text, existing manual export/restore
  retained. No scheduled copying, OCR, recognition or image calls to a model were added.
- System photo picker and external camera capture via a narrowly scoped FileProvider cache directory. No broad gallery or
  camera permission; the selected photo is immediately compressed into the pending form. Original gallery files stay intact.
- Room schema 2 adds `receipts`: bounded JPEG blobs (under Android's CursorWindow size) with metadata queried separately.
  This keeps financial changes, photo changes, delete/undo and restoration in one database transaction. It avoids a second
  file store that could disagree with the journal after a crash. Collection queries never read blobs; exports fetch one at a time.
- Up to 10 per operation, SHA-256 duplicate detection per operation. Repeats/refunds do not implicitly reuse an old photo.
  Draft additions/removals take effect only on save; removal has undo. Viewing supports pinch, pan and double tap.
- Full manual ZIP copies contain exact saved JPEG bytes, metadata/CSV and checksums. Analysis outputs have metadata only;
  a file missing photo bytes cannot pretend to be a restorable full copy. v1 copies remain readable. The existing safety copy
  covers photos too. Restores stage bounded streams under generated temporary names, validate all references and bytes,
  and replace journal plus images atomically. Cancellation discards staged files; failed database writes roll back.
