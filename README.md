# senate-ptr-data

Mirror of US Senate Periodic Transaction Reports (STOCK Act) from the official eFD site
(https://efdsearch.senate.gov). eFD blocks many servers, so a scheduled GitHub Actions workflow scrapes it and commits
the result as JSON to this repo. Consumers read the files anonymously from `raw.githubusercontent.com`.

## Data

Public STOCK Act disclosures, values as eFD prints them (owner, type, amount, ticker).

- `data/senate/{year}.json`: filings of that filing-date year, sorted by `uuid`:
  `{uuid, firstName, lastName, office, filedDate, amendment, paper, url, rows: [{rowNo, transactionDate, owner,
  ticker, assetName, assetType, type, amount, comment}]}`. Paper filings have no rows; a `--` ticker is `null`.
- `data/senate/state.json`: `{lastRunAt, lastSuccessAt, error, backfilledFrom, filings}`. A failed run keeps the
  previous `lastSuccessAt` and sets `error`.

Known uuids are never rewritten; an amendment is its own filing.

## Workflow

`.github/workflows/scrape.yml` runs weekly on Mondays at 06:17 UTC and re-scans the last 60 days (late amendments), fetching
detail pages only for unknown uuids. It makes 1 request per second.

- Backfill: run the workflow manually with `from=2021-01-01`. It goes year by year and writes after each year, and the
  commit step runs even after a timeout, so a re-run resumes from what is stored (about 5,000 pages, 90 minutes).
- Fixtures: run it with `capture=true` to upload the raw home page, a search page and a few PTR pages (amendment,
  paper) as the `efd-capture` artifact. Put them in `src/test/resources/efd/` and adjust the parser tests.

## Local

```bash
./mvnw test
./mvnw -q -DskipTests package exec:java -Dexec.args="data/senate --from 2026-01-01"
```

eFD usually refuses requests from non-US or cloud IPs; a 403 ends the run with exit code 1.
