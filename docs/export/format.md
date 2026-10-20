# SpendCodex data format, version 2

This file describes everything SpendCodex writes out: the full backup, the JSON file, the CSV tables and the package for AI.
It is enough to write your own script that reads the data; you do not need the app's source code.
The same text is inside every archive the app makes (`docs/format.md`), so an archive explains itself.

## Files

A **full backup** (`spendcodex-backup-YYYY-MM-DD.zip`) is the only thing the app restores from:

```text
spendcodex-backup-YYYY-MM-DD/
  manifest.json            what is inside, with a SHA-256 checksum of every file
  data/spendcodex.json     the whole journal: the one file a restore reads
  csv/transactions.csv     every operation, one row each, with the names of what it points to
  csv/accounts.csv
  csv/categories.csv
  csv/tags.csv
  csv/transaction_tags.csv one row per tag on an operation
  csv/receipts.csv         photo metadata and links to their operations
  receipts/<id>.jpg        each compressed receipt photo, with a stable id
  docs/format.md           this description
  docs/schema.json         JSON Schema of data/spendcodex.json
```

A **package for AI** (`spendcodex-ai-YYYY-MM-DD.zip`) holds the operations you chose for analysis:

```text
spendcodex-ai-YYYY-MM-DD/
  README_FOR_AI.md         how to read the files, what the fields mean, what the selection is
  PROMPT.txt               a ready request to paste into the chat
  transactions.csv  accounts.csv  categories.csv  tags.csv
  data.json                the same selection in the JSON format below (scope "partial")
  manifest.json
```

**JSON** (`spendcodex-YYYY-MM-DD.json`) is the data file alone. **CSV** (`spendcodex-csv-YYYY-MM-DD.zip`) is the tables with
this description; **operations as a table** (`spendcodex-transactions-YYYY-MM-DD.csv`) is `transactions.csv` alone.

A selection (a period, some accounts, without notes) is marked `"scope": {"kind": "partial", ...}` and is never accepted as
a backup: restoring it would replace the whole journal with a part of it.

## data/spendcodex.json

```json
{
  "format": "spendcodex",
  "format_version": 2,
  "schema_version": 2,
  "exported_at": "2026-10-20T15:04:05.123Z",
  "app": { "name": "SpendCodex", "version": "0.2.0" },
  "scope": { "kind": "full", "notes_included": true, "receipts_included": true },
  "settings": { "language": "ru", "default_currency": "EUR", "default_account_id": "…", "ai_parsing_enabled": false },
  "currencies": [ { "code": "EUR", "exponent": 2, "symbol": "€" } ],
  "accounts": [ … ],
  "categories": [ … ],
  "tags": [ … ],
  "transactions": [ … ],
  "receipts": [ … ]
}
```

- `format` is always `"spendcodex"`; `format_version` changes only when the meaning of a field changes. A newer app reads every
  older version; an older app refuses a newer file instead of guessing.
- `schema_version` is the version of the app's database the file came from (informative).
- Every list is sorted (accounts by `sort`, operations by date and time), so two exports of the same data are identical but for
  `exported_at`.
- Every `id` is unique within its list and never changes, including across export and restore. References are ids that are in
  the same file.

### Amounts

Every amount is a **string** with a dot and exactly as many decimals as its currency has: `"4.50"` (EUR), `"1200"` (JPY),
`"1.250"` (KWD), `"-20.00"` (a downward adjustment). Read them as decimals (Python `decimal.Decimal`, not `float`).
`currencies[].exponent` is the number of decimals of each currency used in the file.

Amounts in different currencies are never added together by SpendCodex. There are no exchange rates in the data except the ones
the user wrote: what an account actually moved (`account_amount`) and what arrived in a transfer (`to_amount`).

### Dates and times

An operation happened on `date` (`"2026-10-20"`, the user's local day), at `time` (`"14:35"`, local, minutes) in `time_zone`
(an IANA zone such as `"Europe/Berlin"`, the phone's zone when it was recorded). `time` is `null` when it is not known: an
operation written for "yesterday" gets no invented time. Group by `date` for days; do not convert `date` to UTC.

`created_at` and `updated_at` are when the record itself was made and last changed, as UTC instants with milliseconds
(`"2026-10-20T15:04:05.123Z"`). They say nothing about when the money moved.

Operations at the same date and time keep a stable order: by `created_at`, then by `id`.

### accounts[]

| field | meaning |
|---|---|
| `id` | stable id |
| `name` | as the user named it |
| `kind` | `cash`, `card`, `bank`, `savings`, `other` |
| `currency` | ISO 4217 code; every amount of the account is in it |
| `opening_balance` | what was there when the journal started; `null` = not told (a balance is then only the movement since) |
| `archived` | hidden from choices; its operations stay |
| `sort` | its place in lists |

### categories[]

| field | meaning |
|---|---|
| `id`, `name` | |
| `kind` | `expense` or `income`: the kind of operation it can be on (a refund uses expense categories) |
| `parent_id` | the category it is a subcategory of, or `null`. One level only; parent and child have the same `kind` |
| `keywords` | words the quick line recognises the category by |
| `archived`, `sort` | as for accounts |

### tags[]

`id`, `name` (unique, case-insensitive), `archived`. Operations point to tags by id (`tag_ids`).

### transactions[]

| field | meaning |
|---|---|
| `id` | stable id |
| `type` | see below |
| `date`, `time`, `time_zone` | when it happened (see above) |
| `amount`, `currency` | what was paid or received, as written. Positive, except for `adjustment` |
| `account_id` | the account the money left or entered |
| `account_amount` | only when `currency` is not the account's: what the account actually moved, in the account's currency; `null` if not known |
| `to_account_id` | transfers only: where the money went |
| `to_amount` | transfers between accounts of different currencies only: what arrived, in the receiving account's currency |
| `category_id` | `null` = not categorised (it is never filled with a guess) |
| `description` | what it was ("кофе", "rent"), as written |
| `payee` | who was paid or who paid |
| `note` | free text, may have several lines |
| `tag_ids` | sorted list of tag ids |
| `refund_of` | refunds only: the expense this money came back for, if linked |
| `origin` | how the record was made: `line` (legacy local parser), `form`, `ai` (DeepSeek text entry, validated before saving), `repeat`, `reconcile` (legacy balance check) |
| `input_text` | the line as the user typed it, when it came from the quick line or DeepSeek |

### Types

| type | effect on the account | what it means |
|---|---|---|
| `expense` | − amount | money spent |
| `income` | + amount | money earned or received from outside |
| `refund` | + amount | money returned for an earlier expense. For spending totals, subtract it from expenses (of its category); it is not income |
| `transfer` | − amount from `account_id`, + (`to_amount` or `amount`) to `to_account_id` | money moved between the user's own accounts. **Neither spending nor income**: leave transfers out of both |
| `adjustment` | + amount (signed) | the account's figure set right to reality (a forgotten cash purchase, bank fees found later). Neither spending nor income, unless the user says so |

### Legacy account balances

Since app version 0.3, SpendCodex captures expenses and does not calculate account balances. Accounts are payment-method
labels; historical opening amounts and non-expense records are preserved for lossless restoration. The legacy CSV columns
`computed_balance` and `computed_balance_complete` are kept empty. The formula below describes older exports only.

Likewise, `settings.ai_parsing_enabled` is retained from older backups for round-trip compatibility. In 0.3 and later,
explicit text entry always uses DeepSeek when a key is available, regardless of this legacy flag. Categories and some tags
on `origin=ai` records are model classifications; `input_text` preserves the user's original words.

```text
balance = opening_balance (or 0)
        + Σ income + Σ refund + Σ adjustment       (in the account's currency)
        − Σ expense − Σ transfers out
        + Σ transfers in (to_amount, or amount when the currencies match)
```

An operation in another currency counts with its `account_amount`. Without one it cannot be counted, and the balance is
incomplete; older `csv/accounts.csv` files say so in `computed_balance_complete`. `computed_balance` was derived at export time and is not
read back by a restore.

## CSV

RFC 4180: comma separated, CRLF line ends, fields with commas, quotes or line breaks in double quotes (quotes doubled), UTF-8
**without** a byte-order mark, decimal dot. Excel: open via *Data → From Text/CSV* and choose UTF-8. pandas:
`pd.read_csv(path, dtype=str)`, then convert amounts with `decimal.Decimal`.

Text written by the user (names, descriptions, notes, tags, the typed line) that starts with `=`, `+`, `-`, `@` or a tab is
prefixed with `'` in CSV, so a spreadsheet does not run it as a formula. The JSON keeps the exact text.

`transactions.csv` columns: `id, date, time, time_zone, type, amount, currency, account_id, account, account_currency,
account_amount, to_account_id, to_account, to_currency, to_amount, category_id, category, parent_category, description, payee,
tags, note, refund_of, origin, input_text, created_at, updated_at` (`tags` are names joined with `"; "`).

## manifest.json

```json
{ "format": "spendcodex-archive", "format_version": 1, "kind": "backup", "created_at": "…", "app": {…},
  "counts": { "accounts": 2, "categories": 17, "tags": 3, "transactions": 1234 },
  "files": [ { "path": "data/spendcodex.json", "bytes": 123456, "sha256": "…" } ] }
```

Paths are relative to the archive's folder. A restore checks every checksum and refuses a damaged archive.

## Receipt photos (version 2)

`receipts[]` describes each photo: `id` (lowercase UUID), `transaction_id`, `path` (`receipts/<id>.jpg`),
`mime_type` (`image/jpeg`), `bytes`, `sha256`, `width`, `height`, `created_at` (UTC instant), and `sort` (zero-based
position within its operation). `csv/receipts.csv` has the same columns. Photos belong to exactly one operation;
repeating an operation does not copy an earlier receipt. Deleting and undoing an operation restores its photos too.

Photos are compressed locally: JPEG quality 85 (80 or 75 only if needed), short side up to 1600 px, long side up to
8192 px, at most 6 million pixels and 1,500,000 bytes per photo, never enlarged. All eight EXIF orientations are applied
to the pixels; EXIF including location is discarded. There can be up to 10 photos per operation. The saved JPEG is the
photo the app owns; the original in the gallery is left untouched. A full backup preserves the saved JPEG bytes exactly,
without recompressing them during export or restoration.

Only full backup ZIPs contain the image files. Analysis ZIPs, JSON and CSV contain photo metadata, without images;
`receipts_included` is false. If photos are present, those data-only exports are marked partial and cannot replace a full
journal. A bare JSON with receipt references cannot be restored without its images. No receipt is ever sent to DeepSeek.

Restoration validates the manifest, every photo's checksum, size, JPEG dimensions, ids and links before showing its
preview. The preview lists incoming and current photo counts. The database and the bounded photo blobs are replaced
in a single SQLite transaction, so a failed restoration leaves the current operations and photos together. Image files
are streamed individually when exporting, and staged to private temporary files when reading. Archive entry names
are never used as filesystem paths. The read limit is 512 MiB uncompressed, 128 MiB per JSON and 10,000 ZIP entries;
exports enforce corresponding limits, so the app never creates a full copy it cannot read back.

Version 1 backups and bare JSONs are still accepted; they have no `receipts` list or `receipts_included` flag and restore
with no photos. Version 1 apps reject version 2 files. Before replacing existing data, the existing safety-copy mechanism
also preserves its photos; this copy can be saved through the Data screen.

## What is not in an export

The DeepSeek key (used only to authenticate requests, never exported) and the app lock setting (a property of the phone, not of the data).
