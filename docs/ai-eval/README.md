# AI eval of `/v1/enrich`

This is the quality check of tech plan §17.6. It runs a fixed set of task phrasings through the real
route (prompt, schema, Claude call and validation from `core:ai-contract` and `core:ai-claude`). Each run
writes a report to this folder.

## Dataset `enrich-v1.jsonl`

> **DRAFT.** Claude proposed the sizes and dates. The product owner must review them against their own
> durations before the numbers are trusted: change `expected`, then the `status` line in `_meta`.

- 216 phrasings: 72 each in Russian, Ukrainian and English. They include typos, abbreviations (`пн`,
  `tmrw`, `EOD`), mixed languages and a prompt injection.
- Fixed context in the first line (`_meta`): now is 2026-10-06 10:00 (a Tuesday) in Europe/Kyiv, and the
  scale is S=15, M=60, L=180 minutes. Every case is evaluated against this "now", so the dates in the
  references stay valid whenever the eval runs.
- One case per line:
  - `id`, `language`, `text`;
  - `expected`: the validated answer the app should get (size and dates; fragments are only documentation);
  - optional `similarDone` (history), `extracted` (fields the rules already found) and `note` (why the
    case is tricky).
- `extracted` is empty unless a case sets it. The eval measures what the model finds on its own; in the
  app, the rule-based parser fills common dates first.
- Dates follow the resolution rules in the enrich prompt:
  - a bare weekday is the nearest one from today on, and "next Friday" is Friday of next week;
  - the end of the week is Friday;
  - a deadline has a marker ("до", "к", "by", "due", "дедлайн", "термін" and so on);
  - vague periods ("на днях", "next week", "у листопаді") and past days have no date.
- Covered cases: vague single nouns with no size, history that should override the wording, Ukrainian
  "до" meaning "to/for" rather than "until", and deadline times.

`./gradlew :tools:ai-eval:test` checks the dataset in CI without calling the API:
- every case is a valid request;
- every reference answer passes the route's validation unchanged (fragments occur in the text, no past
  dates);
- labels are complete.

## Running

Each case is a paid call: about $0.01 with `claude-opus-5-5` at effort `low`, so about $2 for the whole set.
Run it by hand after changing the prompt or the model, never in CI:

```bash
ANTHROPIC_API_KEY=sk-ant-... ./gradlew :tools:ai-eval:run --args="--limit 20"   # smoke run
ANTHROPIC_API_KEY=sk-ant-... ./gradlew :tools:ai-eval:run                       # full run
ANTHROPIC_API_KEY=sk-ant-... ./gradlew :tools:ai-eval:run --args="--model claude-sonnet-5-5 --effort low"
```

Options: `--dataset`, `--out`, `--model`, `--effort`, `--max-tokens`, `--no-fallbacks`, `--concurrency`
(default 4), `--language ru|uk|en` and `--limit`. The report goes to `docs/ai-eval/report-<date>.md`; a
second run on the same day writes `report-<date>-2.md`. Commit reports together with the prompt or
model change they measure.

## Metrics in the report

- **Size accuracy:** the share of cases with a reference size whose answer has that size. The report
  also shows:
  - accuracy over the answers that have a size;
  - errors off by one size (S↔M, M↔L) and by two (S↔L);
  - a confusion table.
- **"Not sure" share:** the model left the size empty although the reference has one. **Vague texts left
  without a size:** cases whose reference has no size, and how many of them the model left empty.
- **Dates:**
  - exact match, precision and recall for `deadlineDate`, `deadlineTime` and `planDate` after validation;
  - "dates fully correct": all three fields match.
- **Latency:** p50 and p95 of the wall time per call (including SDK retries).
- **Cost:** total and per call, from `ModelPricing` and the reported usage, including declined fallback
  attempts. The report also shows the share of prompt tokens served from the prompt cache.
- **Refusals and failures** by kind, and a table of every mismatched case.

During the beta, the share of AI sizes corrected by users comes from app events, not from this eval.
