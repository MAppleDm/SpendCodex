<img src="docs/icon.svg" width="96" alt="SpendCodex app icon">

# SpendCodex

**English** · [Русский](README.ru.md) · [Download APK](https://github.com/MAppleDm/SpendCodex/releases/latest)

An expense journal with DeepSeek text and receipt-photo entry. From the same family as [FatCodex](https://github.com/MAppleDm/FatCodex).

Write **“coffee 4.50, taxi home 12 yesterday”** and send, or photograph a receipt. DeepSeek extracts expenses, chooses
categories and adds useful tags. Validated records save immediately. Swipe a record right to delete it; tap it to edit.
No chat or analytics dashboard.

## Get started

1. Choose your default currency, used when the note does not name one.
2. Tap **Set up DeepSeek** and save your API key. A link to obtain a key is on that screen.
   Text and photo entry need internet and a funded DeepSeek API account.
3. Type naturally and send with the arrow or Enter. Typing alone never sends a request.

Examples: `coffee 4.50`, `taxi 12 yesterday`, `groceries 32.90 EUR`, `lunch 15 #work`, `coffee 4.50, bread 3`.
Tags are optional in your text: DeepSeek adds relevant tags and reuses existing vocabulary.
Unclear amounts, currencies or dates need clarification. An amount absent from the original note cannot be accepted
through the AI confirmation action. Network failures preserve the note for retry.

**Manual entry** is the page-with-plus icon beside search in the header. It works offline without a key.
The form includes amount, currency, category, date/time, merchant, tags, notes and receipt photos.
Stored records support editing, repeating and purchase refunds. A swipe right (or Delete on the record) removes one,
and the line that follows can bring it back.

## Expenses only

The header shows today's spending per currency. SpendCodex counts spending, not balances: there are no payment methods,
no default payment method and no card or cash balances.
There is no new income, own-account transfer or balance-adjustment entry. Purchase refunds are created from the
original expense and displayed separately.

Older non-expense records remain intact. Find them under **Search → Type → Previously recorded operations**;
full manual copies retain them. Default search and the main feed show expenses and refunds.
Search combines text, dates, categories, tags and currencies, and can export its results.

## Receipts

**Photograph a receipt**: the camera icon left of the input line → **Camera** or **From gallery** (up to 10 photos; a long
receipt can take several), or share photos to SpendCodex from the gallery. DeepSeek reads each receipt and splits it by
category: a supermarket receipt becomes, say, “groceries 840.50” and “household 450”, each with the shop, the date and
time on the receipt, the items in the note and the photo attached. You check the result and tap **Record**. If the
parts do not add up to the receipt's total, or a photo was not read, it says so before you record. Photos go to DeepSeek
downsized and without metadata.

To attach photos by hand, open a record, then **Receipt photos → Camera / From gallery**, up to 10 photos. Images are compressed on the phone;
gallery originals stay intact. Tap a thumbnail to zoom. Photo changes apply on save and removal has undo.
Repeating a purchase does not duplicate its old receipt.

## Data and precision

- Amounts are integer minor units with currency-specific precision. Excess decimals are refused, never rounded.
- Currencies are never added together or automatically converted. Unknown times stay unknown.
- Each batch saves atomically; repeated taps cannot duplicate it.
- The original note is retained with `origin=ai`. Categories and tags are editable model classifications.

Each send submits **your typed note including its amounts**, today's date, time zone, default currency,
categories with keywords and existing tag names to DeepSeek. Past records and receipt images are not sent.
The key is encrypted on the device, used for authentication and excluded from exports. Saved records stay on the
phone; there is no device sync.

Under **Settings → Data**:

- **Backup**: a manual ZIP containing all records, photos, JSON, CSV, format documentation and checksums.
- **Restore**: validate the copy, preserve current data in a safety copy, then replace the journal atomically.
  Older copies without photos still work.
- **Export for analysis**: an external-AI package, JSON or CSV, with period and note controls. Analysis exports include
  photo metadata; image bytes are included only in full copies.

The format is documented in [docs/export/format.md](docs/export/format.md). RU/EN, biometric/PIN lock and GitHub updates
are included, and two looks under **Settings → Theme**: black & white or avocado 🥑 (adapted from the Avocado themes for KDE Plasma and Omarchy), both following the phone's light/dark mode.

## Build

Android 8.0+, Kotlin/Compose/Room. Build with JDK 17+ and Android SDK 37:

```bash
cd android
./gradlew assembleRelease
```

See [docs/development.md](docs/development.md) for tests and signing. Without a custom keystore the APK uses the existing
debug signing key. Automated checks use a stand-in DeepSeek server, not a live key or a physical phone.

MIT, see [LICENSE](LICENSE).
