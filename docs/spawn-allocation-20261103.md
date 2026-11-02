# Spawn allocation incident — 2026-11-02/03

## Cause and repair

ArcContracts 0.1.19 refreshed its read models every 20 ticks. Each refresh fully
validated every cached submission journal record, including historical dynamic
payout arithmetic and repeated Base64/SHA-256 escrow validation. On Spawn,
68 retained records produced about 35 MiB/s of allocation without any players.

[0.1.20, 26c66202](https://github.com/alexey-va/ArcContracts/commit/26c66202fea8c61e1d71fcfd5c82721554cc1915)
caches successful validation for an unchanged record object and a detached copy
of its payload list. A new Redis object, even with the same ID/revision, or an
in-place payload-list change triggers full validation. Deleted records are
evicted, failures do not publish a new cache, and shutdown clears the cache.
Age, states, sums, duplicate IDs and capacity/overflow checks still refresh.
Startup/reload audits and financial transitions keep their existing validation.
Prices, payout rules, journal data, escrow and balances were not changed.

## Verification and activation

- 249 unit tests and one Redis integration test passed, with no skips; consumer
  architecture, production JAR and fixture verifiers passed.
- [CI and immutable Maven publication](https://github.com/alexey-va/ArcContracts/actions/runs/41522429607)
  passed for the exact source cutoff. Independent source review found no issue.
- The detached public-Maven build and anonymous public runtime JAR are identical:
  SHA-256 `a8a7460caba4465be6b609d616ddf20e2f31df7f811436a67fed84f73f5134f5`.
- Spawn disk delivery: RCNet operation
  `06447f6053c43fa99a58ca5c7fa008b65764ac2295ca831d5d7b3b9b3fa4b34f`, native
  manifest `jar-20261102T205244Z-381438`. The 0.1.19 root JAR was retired.
- Separate restart operation
  `46214854e72a2fec12a5f590102ae4ff8872426216803426565e4ffe49ed3c73`
  reached `Done` at 23:57:47 Moscow on November 2, PID 385706. Complete runtime
  inventory reports enabled 0.1.20; exact root JAR hash matches. Contracts report
  ready, Redis connected and 44 resource orders. Content-health counts match the
  pre-update snapshot. No other server was restarted by this task.

## Allocation readback

Spark async allocation sampling used all threads and the default 524,287-byte
interval. Both comparable captures had zero players, 1,746 chunks and 7,096
block entities. These are estimated allocation rates, not retained heap sizes.

| Capture, Moscow time | Version | All threads | ArcContracts |
|---|---|---:|---:|
| Nov 2, 23:46:21–23:47:21 | 0.1.19 | 108.87 MiB/s | 35.22 MiB/s |
| Nov 3, 00:00:47–00:01:48 | 0.1.20 | 74.16 MiB/s | 6.03 MiB/s |

Contracts allocation fell approximately 83%. The final warm window recorded
20 TPS, median tick 8.2 ms and maximum 44.9 ms. An earlier post-restart capture
contained unrelated startup work and a 6-second tick; its total is not used for
the comparison. The unchanged remaining read-model work is not allocation-free.

Profiles remain under Spawn `plugins/spark/`: before
`profile-2026-11-02_23.47.22.sparkprofile` (SHA-256
`76544c439553e68815d94292b9bae9a4a8635a61c902bd901a8aa445d7993aa0`), after
`profile-2026-11-03_00.01.49.sparkprofile` (SHA-256
`c9b41f256a96c52acdfbfd1e0df6a4a69e65ff97d41e989c22f6ee2f596a0196`).
Decoded tree references and allocation-weight totals were validated locally.

## Related WorldView state and retained heap

The owner explicitly disabled ArcWorldView on Spawn. Its 0.1.5 JAR was moved
recoverably outside active plugins; configuration and data were retained.
WorldView remains absent from the complete runtime inventory and its listener
is closed. [WorldView 0.1.6](https://github.com/alexey-va/ArcWorldView/commit/eac5e994ed63dc6992158a9be756338ddec06899)
deduplicates complete block states before string conversion: a representative
32,768-cell regression reduced string conversions to eight while preserving
the exact wire hash. Its tests and CI passed; runtime improvement is unmeasured
because it remains disabled. A verified candidate is stored outside loading at
`/home/freedeeml/.local/share/rcnet/workspace/ArcWorldView-0.1.6.disabled.jar`.

The initial live histogram separately measured 4.17 GiB / 82 million retained
objects. These fixes address allocation churn; they do not establish removal
of that baseline. Large retained groups include YAML parse structures,
preloaded EliteMobs regional definitions and three reserved ArcFarms expedition
scenes. No such content or recovery data was removed.
