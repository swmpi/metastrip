# Offsite

**Remote roles in the Hacker News "Who is Hiring" threads.**

A search tool over Hacker News monthly hiring threads that filters by work
arrangement — remote, hybrid, or onsite — and shows you the exact words it used
to decide.

No model, no API key, no training. PHP 8.3 and a free public endpoint.

## Why this is harder than a keyword search

Every posting in these threads is free text. There is a loose convention —
`Company | Role | Location | REMOTE | Salary` — that maybe two thirds of posters
follow, and the rest write paragraphs.

Searching for the word "remote" fails in four distinct ways:

| Posting says | Naive match | Reality |
|---|---|---|
| `This is not a remote position` | remote | onsite |
| `Hybrid — 3 days in the office` | no match | hybrid |
| `Remote-friendly` | remote | usually hybrid |
| `We hunt remote code execution bugs` | remote | jargon, tells you nothing |

That last one matters more than it looks. These threads are written by engineers,
so "remote desktop", "remote server", "remote attestation" and "remote procedure
call" all show up in postings for fully onsite jobs.

## Running it

```bash
php -S localhost:8000        # any PHP 8.1+ with mbstring and curl
```

Open <http://localhost:8000>. Leave the keyword blank to see every remote
posting in the current thread.

No network access? `?demo=1` runs the same pipeline over the bundled test
postings, so the interface works offline.

```bash
php eval/run.php             # accuracy, per-class metrics, confusion matrix
php eval/run.php --verbose   # every case with its evidence
php eval/run.php --json      # machine-readable, for diffing runs
```

## How it works

```
HnClient       Algolia API → thread → top-level comments (cached 6h on disk)
PostingParser  free text → company, role, location, salary, tech stack
RemoteClassifier   text → remote | hybrid | onsite, + confidence + evidence
JobSearch      ties them together, filters, ranks by weighted keyword match
```

### The classifier

Three-way, not boolean. Hybrid is the single biggest source of false positives
in a remote search, so it gets its own class instead of being forced into yes/no.

Rules are tiered, and the first tier that fires decides:

| Tier | What it catches | Example |
|---|---|---|
| 0 | the pipe header field | `\| REMOTE \|` |
| 1 | explicit denial | `not a remote position`, `onsite only` |
| 2 | hybrid markers | `hybrid`, `2 days in office`, `remote-friendly` |
| 3 | unambiguous remote | `100% remote`, `work from anywhere` |
| 4 | a bare mention | `remote` on its own |

Technical jargon is masked out before any rule runs. Geographic constraints
(`Remote (US)`, `within 3 hours of CET`) are extracted as a separate field
rather than changing the classification — a US-only remote job is still remote,
you just need to know before you apply.

Every verdict carries the phrases that produced it. The interface highlights
them inline, colour-coded by tier. When tiers disagree the card says so and
opens the evidence list by default.

## Results

48 hand-labelled cases, currently 48/48.

```
  class     support     prec   recall       f1
  remote         20   100.0%   100.0%   100.0%
  hybrid         12   100.0%   100.0%   100.0%
  onsite         16   100.0%   100.0%   100.0%
```

**That number is overfit and should not be quoted without this paragraph.**
The rules were tuned against these exact cases, so 100% means "no known failures
on cases I have already looked at," not "100% accurate in the wild." The honest
version is a held-out set: label 40–50 postings from a live thread, seal them
until tuning is finished, and report that score instead.

### What the harness actually caught

The run went 81.3% → 93.8% → 97.9% → 100%, and the largest single jump was not a
rules change.

**81.3% → 93.8%** was a preprocessing bug. `strip_tags()` removed `<p>` without
leaving whitespace, so `London<p>Remote-friendly` became `LondonRemote-friendly`.
That destroys the `\b` word boundary every rule depends on, and it silently cost
twelve points of accuracy across a third of the test set. Spot-checking a few
outputs by hand would never have found it — the failures looked like reasonable
misses, not like a broken pipeline.

**97.9% → 100%** was a subtler design fault. A body rule matched the `| REMOTE |`
pipe field that the tier-0 header check had already read, so header evidence
appeared twice and masqueraded as independent corroboration. That blocked
legitimate hybrid downgrades: a posting headed `REMOTE` whose body said "two days
in the office each week" stayed classified remote. Deleting the duplicate rule
fixed it.

Both are the kind of bug that survives indefinitely without a scored test set,
because the output stays plausible.

## Limitations

- Rules, not learning. Novel phrasings need a new rule, and every new rule risks
  regressing an old case — which is what the harness is for.
- The labelled set is synthetic. It is modelled on real HN phrasing but written
  by hand to cover known edge cases, so it over-represents hard cases and
  under-represents the boring majority.
- Only English postings.
- `Hybrid or Remote` style postings that genuinely offer both are resolved in
  favour of remote. That is a product decision, not a correctness one — someone
  filtering for remote wants to see them.
- Compensation parsing handles USD, GBP and EUR ranges. Other currencies and
  equity-only offers are ignored.

## Data and terms

Postings come from the [Hacker News Algolia API](https://hn.algolia.com/api),
which is free and needs no key. Responses are cached for six hours; the threads
change monthly, so refetching on every keystroke is both slow and rude to a free
service. Postings belong to the people who wrote them and every card links back
to the original comment.

Not extended to Indeed or LinkedIn: both prohibit scraping in their terms and
block it aggressively. Greenhouse and Lever expose public JSON per company board
and are a reasonable next source.

## Next

- A held-out test set from a live thread, so the accuracy figure means something
- Inter-annotator agreement on the ambiguous cases — some postings genuinely
  cannot be classified from their text, and it would be useful to know what
  fraction
- Salary normalisation to a common annual figure for sorting
- Watch a keyword across several months and flag new postings

## License

Copyright (C) 2026 Offsite contributors.

Offsite is free software: you can redistribute it and/or modify it under the
terms of the [GNU General Public License](LICENSE) as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any
later version. It comes with **no warranty**; see the license for details.

Postings retrieved from Hacker News are the property of the people who wrote
them and are not covered by this license.
