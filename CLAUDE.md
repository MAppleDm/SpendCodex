# SpendCodex

A personal expense journal for Android (Kotlin, Compose, Room, Hilt) in `android/`. Product decisions:
[docs/decisions.md](docs/decisions.md); the author's latest words override [docs/brief.ru.md](docs/brief.ru.md).

## Product rules

- **Spending, not balances.** "Тут не учёт баланса, а учёт расходов." SpendCodex counts what was spent. Do not add payment
  methods, a default payment method, card/cash/bank balances, transfers, adjustments or opening amounts, in the UI or in
  the DeepSeek request. The schema keeps one unseen account per record only for format compatibility.
- Expenses only (and refunds of a purchase); no new income.
- Every typed line goes to DeepSeek (the user's own key), which reads expenses, picks categories and adds tags. Manual
  entry is the secondary icon beside search.
- Old records and backups are never deleted or reinterpreted by an update. Money is `Long` minor units, never floating point.
- Backups were not asked for: keep the existing feature, do not push it.

## Working here

- FatCodex (`P:\projects\vibefreewill\FatCodex`) is a read-only design reference: never modify it.
- Tests: `cd android && ./gradlew test` (see [docs/development.md](docs/development.md)).
- Commit, push and release only when the author asks. Releases: tag `vX.Y.Z`, assets `SpendCodex-X.Y.Z.apk` and
  `SHA256SUMS.txt`, signed with the same key so updates install over the old version.
- **Everything posted to GitHub is in English**: commit messages, release titles and notes, issues, pull requests and
  comments. The author asked for this (2026-10-21). Talk to the author in Russian; the app itself stays RU/EN.
