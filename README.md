# SpendCodex

**English** · [Русский](README.ru.md)

A finance journal in a single line of text. From the same family as [FatCodex](https://github.com/MAppleDm/FatCodex).

Write it like a note, `coffee 4.50`, and SpendCodex shows what it understood word by word: expense, 4.50 €, Card, "Cafes & restaurants", today 16:41. One tap records it. No forms, no required fields, no account.

**Record everything. Own your data. Analyze anywhere.** The app collects and preserves the financial truth; an external AI (ChatGPT, Claude, Gemini, your own script) interprets it.

## How it feels

- `coffee 4.50`: an expense in the default account's currency, categorised by the word
- `taxi 12 yesterday`: dated yesterday, with **no invented time**
- `+salary 3000`: income
- `card → cash 100` or `100 from card to cash`: a transfer between your own accounts, neither spending nor income
- `cash → dollars 100 108`: an exchange, 100 € left and $108 arrived
- `refund shoes 59.99`: money back for a purchase
- `lunch 15 $ = 13.80 €`: paid in dollars, the euro account moved 13.80
- `coffee 4.50, croissant 3`: two operations in one line
- `#tags` and `// a note`; dates like `fri`, `oct 12`, `15/10`, `2026-10-01`, `3 days ago`

Every word the app understood sits above the line as a chip. Grey means a default, white means you wrote it, red needs attention. Tap a chip to change it. Doubtful readings are stated, never silently guessed. The full form (payee, note, tags, refund link, the account amount for another currency) is one tap away, and so is every record for editing, repeating, refunding or deleting with undo.

The form has **Receipt photos → Camera / From gallery**, up to 10 photos per operation. Images are compressed locally;
gallery originals stay unchanged. Tap a thumbnail, then pinch or double tap to read the receipt. Photo changes take effect
when you save; removal has undo. Repeating an operation does not duplicate its old receipt.

## Why the numbers can be trusted

Amounts are whole minor units (no floating point) with each currency's own number of decimals. Currencies are never added together. A transfer is never spending, a refund is never income. Unknown times stay unknown. Records keep their accounts and categories (enforced by SQLite foreign keys), sending the same line twice stores it once, and a deletion can be undone exactly.

## Your data

No account, no server. The network is used only for DeepSeek, which is off by default, uses your own key, and is used only for lines the rules could not read; and for the GitHub update check, only when you tap it.

**Data → Backup** saves `spendcodex-backup-YYYY-MM-DD.zip`: the whole journal as JSON, CSV tables, the format description and a JSON Schema, every file checksummed. It restores everything, on this phone or another, with a preview and a safety copy of what it replaces.

Full manual copies include the saved receipt images and their links. All photos are checked before restoration; a missing
or damaged photo refuses the copy without changing the journal. Older copies without photos still work. Analysis exports
include photo metadata without images. Receipt images are never sent to DeepSeek.

**Data → Export for analysis** can be limited to a period, some accounts, with or without notes. The **For AI** package includes instructions for the model and a ready request; JSON and CSV are also available. Before anything is saved, you see what it holds.

The format is documented in [docs/export/format.md](docs/export/format.md).

## Limits

The journal lives on one phone. Photos are compressed JPEGs, up to 10 per operation; legibility depends on the original
capture. There are no exchange rates. This is an early personal project, tested on an Android 16 emulator.

## Build

```bash
cd android
./gradlew assembleRelease
```

See [docs/development.md](docs/development.md).

## License

MIT, see [LICENSE](LICENSE).
