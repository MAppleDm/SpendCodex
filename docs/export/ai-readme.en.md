# Finances from SpendCodex: how to read these files

This is an export of the SpendCodex expense journal. DeepSeek parses text entry; analysis is up to you.
The data is primary: nothing is recalculated, rounded or filled in with guesses.

## What was exported

- Period: {{period}}
- Accounts: {{accounts}}
- Operations: {{count}}, currencies: {{currencies}}
- Notes and the text as typed: {{notes}}
- Exported: {{exported_at}}

## Files

- `transactions.csv`: one row per operation, with the names of its account, category and tags. The main file for analysis.
- `accounts.csv`, `categories.csv`, `tags.csv`: reference tables. Accounts are payment-method labels. Balances are not
  calculated; the legacy `computed_balance` and `computed_balance_complete` columns are empty.
- `data.json`: the same in structured form, with every link by id.
- `receipts.csv` and `data.json.receipts`: photo metadata and operation links. Images are not included; do not infer receipt
  contents from filenames or hashes.
- Field reference: see "Fields" below.

## The main rules

1. **Types** (`type`):
   - `expense`: money spent;
   - `income`: money received from outside;
   - `refund`: money returned for an earlier purchase. Not income: subtract it from the expenses of its category.
     `refund_of` points to the purchase when it was linked;
   - `transfer`: money moved between the user's own accounts. **Neither spending nor income.** Leave transfers out of both;
   - `adjustment`: an account's figure set right to reality. Neither spending nor income.
2. **Currencies.** `amount` is in `currency`. Never add amounts in different currencies without a rate. The only rates in the
   data are the ones the user wrote: `account_amount` (what the account actually moved, in its own currency) and `to_amount`
   (what arrived in a transfer). For an overall total, sum per currency or state the rate you use.
3. **Amounts** are strings with a dot (`"4.50"`). Positive, except `adjustment`, which is signed.
4. **Dates.** `date` is the local day of the operation; `time` is local time or empty when unknown (that is normal, not an error).
   Do not convert `date` to UTC. `created_at`/`updated_at` are when the record was made and edited, not when money moved.
5. **Categories.** An empty `category` means uncategorised, not "other". `parent_category` is the parent of a subcategory.
6. **Limits.** Income and transfers may come from older versions; never count them as expenses. For `origin=ai`, categories
   and some tags are model classifications, not independently established facts. `input_text` is the original note.
   There may be gaps: do not invent missing operations and do not present estimates as facts.

## transactions.csv fields

`id`, `date`, `time`, `time_zone`, `type`, `amount`, `currency`, `account`, `account_currency`, `account_amount`,
`to_account`, `to_currency`, `to_amount`, `category`, `parent_category`, `description` (what it was), `payee` (who was paid or paid),
`tags` (joined with "; "), `note`, `refund_of`, `origin` (how the record was made: `line` quick line, `form`, `ai` the line read by
DeepSeek and validated by the app, `repeat`, `reconcile` a legacy balance check), `input_text` (what was typed), `created_at`, `updated_at`.
The `*_id` columns are stable ids that link the files.

Text starting with `=`, `+`, `-` or `@` is prefixed with an apostrophe in CSV (to keep spreadsheets from running formulas);
`data.json` has it unchanged.
