# Decisions

The person's clarifications override the [original AI-generated brief](brief.ru.md). The current product direction is
AI-first expense capture (0.3), with readable receipt photos, manual export/restore and secondary manual entry.

## The rule: spending, not balances

SpendCodex counts **what was spent**. It is not bookkeeping of money on cards, in cash or in bank accounts, so nothing
that only makes sense for balances belongs in it: no payment methods, no default payment method, no balances, transfers,
adjustments or opening amounts. The author asked for this directly ("тут не учёт баланса, а учёт расходов", 0.3.2) after
seeing "Способы оплаты" and "Оплата по умолчанию" in the settings. A new feature that asks "paid how?" or "from which
account?" goes against this rule.

- Since 0.3.2 there is no payment method anywhere: not in settings, the form, search, export choices, journal rows or the
  DeepSeek request and answer. Account names are not searched either.
- The stored format still has one account per record (Room schema and JSON format 2), so new journals get one unseen
  account ("Расходы"/"Spending") and every new record is booked to the default one. If none is active, the default is
  unarchived, or one is made, so a record can always be saved. Repeating or refunding a purchase of an archived account
  uses the default one.
- Older journals keep their accounts and every record's link to them, untouched; backups still carry them. Only old
  transfers and adjustments, which are about accounts by nature, still name theirs in the form and in search results.

## Product and the FatCodex reference

- Android: Kotlin, Compose, Room, Hilt; `app`, `core-ui`, `data` modules, matching FatCodex's engineering approach.
- One journal, a bottom text input, monochrome Inter typography, thin rules and orange timestamps. FatCodex was read
  as a reference and is not modified. RU/EN, biometric lock, GitHub update verification and export-for-analysis are retained.
- Every explicit send goes to DeepSeek using the user's key. There is no local-parser fallback, chat or financial advice.
  DeepSeek extracts expenses, picks an existing expense category and adds useful tags, reusing existing tag names.
- Validated ordinary batches save immediately, with no "undo" line: the new rows are the confirmation (0.3.4, the author
  found the line under every new record awkward). A record is deleted by swiping it right in the journal (also a TalkBack
  action); a deletion, by swipe or on the record page, offers "bring back" for a few seconds. Unknown references require review. Invalid amounts, currencies,
  dates, tags or an amount absent from the note block the AI save action. Income/transfer/balance intent is refused.
- Manual entry is the page-with-plus action beside search. It also handles corrections and receipt attachment.
- The main feed and default search contain expenses and purchase refunds. No new income/transfer/adjustment picker,
  income category creation, opening balance entry or reconciliation UI exists. There are no payment methods (see above).
- Previously stored non-expense types remain editable and searchable through explicit legacy type filters. JSON data,
  links, opening amounts and old settings are preserved; updating never deletes or reinterprets existing records.

## DeepSeek boundary

- A forced `record_operations` function returns one complete batch (up to 50 expenses), or an explicit clarification status.
  An invalid item refuses the whole batch; malformed/duplicate/truncated answers are refused rather than partially saved.
- The request contains the submitted note, date, time zone, language, default currency, expense-category paths/keywords
  and existing tag names. No history, payment methods, opening balances or photos are included, and the answer has no
  payment-method field: how something was paid is never recorded.
- The key is AES-GCM encrypted with Android Keystore, used for API authentication, excluded from exports and Android backup.
  A saved key is enough to use primary input; the old `ai_enabled` setting survives only for export/restore compatibility.
- Typing alone never calls the API. A synchronous busy guard prevents concurrent sends. Editing or cancelling cancels the
  HTTP request. Stable IDs derived from a batch UUID keep retries and repeated confirmations idempotent. Saving freezes
  input briefly so the text cannot be cleared underneath a newer note. Failures retain the note and expose retry/manual entry.
- `origin=ai` and `input_text` preserve provenance. Export instructions identify categories and tags as classifications,
  so downstream models do not treat generated labels as independently confirmed financial facts.
- Processing input needs a network connection. Stored history, manual entry, photo handling and export/restore remain usable
  offline. No backend, cloud journal or device sync was introduced: the requested AI-first change concerns input.

## Money and time

- Spending is shown without a minus sign (since 0.3.4): in the journal, the day's total, search totals and messages an
  expense is "450 ₽", not "−450 ₽", since nothing is being taken from a balance. Refunds and old income keep their "+".
- `Long` minor units, ISO 4217 exponents, exact decimal-string parsing. Excess precision is refused instead of rounded;
  currencies are kept separate. The maximum per-operation amount is 10^15 minor units.
- The schema retains older account amounts and transfer fields for lossless compatibility. Balance computation is not used
  by screens or exports. Legacy CSV balance columns remain present and empty to avoid changing existing column layouts.
- `date` is the person's local day; `time` is a minute or null when unknown. A past day without time stays unknown.
  `created_at` and `updated_at` are UTC milliseconds. Order is date, time, creation time, ID.

## Storage and search

- Room foreign keys protect referenced accounts/categories/tags; archiving preserves old records. Inserts use IGNORE on
  stable IDs; edits use upserts without REPLACE/cascade deletion. Unchanged edits preserve their timestamps.
- Expense batches save atomically. The schema remains version 2, so existing installs need no destructive migration.
- Search uses normalised text, escaped LIKE patterns and bound filters; all words must match. Pages load 200 records.
  Pending filter changes do not expose stale results or exports from a different selection.

## Receipt photos and manual copies

- System photo picker plus external camera via a narrow FileProvider cache directory. No broad gallery/camera permission.
  Original images remain intact. Compression removes EXIF and respects all eight orientations.
- Up to 10 bounded JPEGs per record: short edge 1600 px, long edge 8192 px, 6M pixels, 1.5 MB each. Metadata queries exclude
  image blobs. SHA-256 prevents duplicate photos on one record; repeats/refunds do not copy an old receipt implicitly.
- Pending photos apply together with form Save; removal has undo. The viewer supports pinch, pan and double tap.
- Room stores bounded image blobs, so financial/photo edits, deletion/undo and restore remain atomic after interruption.
- Full manual ZIPs contain exact JPEG bytes, metadata, JSON, CSV, format/schema and per-file checksums. Analysis exports
  contain photo metadata without images. Version 1 copies remain readable; current Room and export format are version 2.
- Restore stages bounded streams under generated private names, validates every field/reference/photo/checksum, rejects
  partial exports, saves a safety copy, then replaces everything in one transaction. Cancellation discards staged files;
  database failure rolls back. Export → restore → export retains financial and photo data.

## Looks

- Two palettes, both following the phone's light/dark mode: **black & white** (the FatCodex look, the default) and
  **avocado**, adapted from published themes rather than invented: the light variant from the "Avocado" KDE Plasma colour
  scheme (a light cream of avocado flesh for the page, stone-brown text and times, skin-green actions), the dark one from the "Avocado" Omarchy theme
  (true black, light yellow-green text, bright green actions, warm yellow times). Sources and values: [licenses.md](licenses.md).
- Every colour is a token in `core-ui/Theme.kt`; `accent`/`onAccent` colour the send button, chosen chips, text actions and
  question bars. In black & white the accent equals the text colour, so that look is unchanged.
- The choice is a property of the phone (`theme` in the `app` preferences), like the lock: not part of the journal, not in
  exports, kept by "erase everything". `PaletteTest` keeps every text colour at WCAG 4.5:1 on its background in all four
  variants.

## Deliberate limits

No payment methods, account balances, new income bookkeeping, bank integration, exchange-rate service, charts, budgets, forecasts, chat,
financial advice or device sync. Real API behaviour is checked through the documented protocol and stand-in server tests;
no live user key is used by automated verification.
