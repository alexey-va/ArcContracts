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

## Retained-heap follow-up — November 3

The subsequent private live HPROF was analysed on the server with Eclipse MAT
1.17.0. It contains private diagnostic data and was not uploaded publicly.
The runtime uses JDK 25 compact object headers, which this MAT parser does not
model. MAT byte values below are estimates for finding owners, not physical
JVM shallow-byte measurements; overlapping retained groups must not be added.

The largest observed named retainers included EliteMobs custom-boss definitions
(about 574 MiB), regional-boss holders (about 503 MiB across 91,083 instances),
power definitions (about 162 MiB) and item definitions (about 143 MiB). These
are preloaded definitions and holders, not 91,083 spawned world entities.
There were 94,745 configured regional points, including 85,770 references to
foreign worlds. A public regional flag would change native mechanics and is
not a safe memory-only workaround. Bukkit YAML constructors also retain parse
nodes and source marks; this data overlaps configuration-owner totals.

The owner declined third-party JAR modifications. No EliteMobs, Purpur or YAML
runtime patch was made. The lazy regional-holder proposal, including world-load
materialization and preservation of all saved points, was filed as
[EliteMobs issue 237](https://github.com/MagmaGuy/EliteMobs/issues/237).

First-party findings and prepared changes:

- ArcFarms 0.47.6, source `fcb17a0e1a110a5cc7c83dc3baace470a7dbfada`, replaces
  boxed position indexes and frozen map entries for 568,190 expedition cells
  with primitive indexes and array-backed maps. Recovery DTOs, saved codecs,
  scene order and reserves remain unchanged. Fixed workpiece styles and
  immutable furniture fixtures are reused. 66 focused tests and packaging
  checks passed; the public Maven JAR SHA-256 is
  `4b39f61f39bf5290d724981d799cb435067ad48726a778e7e2740d1294d7d98e`.
- ArcExcellentCrates 0.14.65 caches unchanged configuration by exact bytes rather
  than reparsing it each tick interval. 0.14.66 (`37fdcfa`) retains compact
  metadata for durable DELIVERED openings and lazily reads full history from
  disk on the storage executor. The previous 1,150-record ledger held about
  103 MiB in MAT, mostly key/delivery inventory preimages; 1,147 records were
  delivered and three were MAIL. Disk JSON and duplicate-payout safeguards are
  retained. 60 focused archive/recovery tests and CI passed.
- ArcContracts 0.1.21, source `9031dc392a448b13deb9e35b2773a56af6aaff54`, uses
  standard `HexFormat` instead of per-byte `Formatter` calls for SHA-256 weekly
  sorting. Golden rankings for two weeks remain identical. Full unit/Redis
  integration/packaging CI and immutable Maven publication passed. Public JAR
  equals the CI artifact, SHA-256
  `a9b30b4e78da10f7199b45501ad74296ae5c20fa4b4c83372c82e70edecefb72`.

Additional retained first-party data was legitimate rolling telemetry
(ProductInterest about 45 MiB, 4,870 players / 6,090 daily records) and physical
periodic-key receipts (about 15 MiB). No player analytics or key receipts were
silently deleted. These estimates do not establish an allocation leak.

At 00:55:40 Moscow, a fresh native `GC.class_histogram` for PID 385706 measured
81,452,094 live objects / 4,430,819,824 bytes (4.1265 GiB). One ordinary player
was online in the immediately preceding complete observation. The exact
histogram remains in the protected diagnostic directory on Spawn.

A comparable empty-Spawn all-thread allocation capture at 00:08:51–00:10:22
lasted 90.21 seconds with the same 524,287-byte sampling interval. Its validated
semantic owner totals were 76.67 MiB/s: Citizens 20.21, ArcFarms 13.64,
ArcExcellentCrates 9.16, ArcContracts 6.08 and other paths 27.57. The saved
profile SHA-256 is `6ca00064ccf903393fd1951ecf5e954b33ee453efb0ff5322c0fc89f199907c2`.
Delivery, activation, MAIL expiry policy and final before/after readback will
be recorded separately when complete.


### Unclaimed reward expiry policy

The owner explicitly authorised forfeiture after seven days of inactivity.
ArcExcellentCrates 0.14.67, source
`0647f6c7a418f471f750e29b28da0948b05baae2`, adds a truthful terminal EXPIRED
state, compact spent-ID receipt and read-only history label. Only same-revision
MAIL without a delivery witness can expire, when both network last-seen and
MAIL update are at least seven days old. REVIEW and DELIVERING are preserved.
The selected reward is not rerolled, a spent key is not refunded, and expiry
emits no delivery analytics or native inventory/currency action.

The reader uses the existing proxy-authored PlayerActivityStore and validated
full `arc.proxy_player_list` roster from origin `proxy`. Roster observation and
activity-read age are limited to ten seconds, including storage backlog;
unknown/future dates, backward clocks, bad/foreign/stale rosters and Redis
failures preserve rewards. A minute timer retries startup failures. A player
active on Survival is excluded. This is an offline periodic sweep; PostLogin
already updates last-seen, so it does not infer the previous session's absence
from a new login timestamp.

The read-only pre-activation cohort had two item entitlements whose owners were
inactive about 29.6 days, and one recent gift entitlement whose owner was
inactive about 1.3 days. Native item counts were not inferred from IDs. The
[assessment and calculator output](https://github.com/alexey-va/ExcellentCratesItemsAdder/tree/0647f6c7a418f471f750e29b28da0948b05baae2/docs)
keep each reward right separate: the two eligible rights conditionally become
unclaimable, the recent right stays; direct vault/tokens/crystals/XP movements
remain zero. There is no SELL valuation or change to the planned SELL-to-contract
transition. Focused cases, independent review, full unit/package/consumer CI and
Redis integration passed. Final JAR SHA-256:
`bbdf1ee4873d54990a470305ce9e26b69309d03e97831904676a0483d4830844`.

After the first persisted EXPIRED record, an older binary cannot decode that
new enum. A file-only downgrade is not a compatible rollback; preserve records
and use a compatible binary or fix forward.


### Final activation and allocation readback

RCNet deployment `c5bef785c2862a62d25f3cd61ecaa775352e258ad2766571c72cf0f57296630e`
completed delivery and one Spawn restart at 01:15:19 Moscow (96.262-second
startup), PID 453800. A fresh complete observation was empty before activation.
All three candidate root JAR hashes match, and the complete 111-plugin runtime
inventory reports enabled ArcFarms 0.47.6, ArcExcellentCrates 0.14.67 and
ArcContracts 0.1.21. The coordinated ArcEvents 0.5.39 on-disk candidate also
activated; its hash matches its owner's receipt. ARC remains 1.4.520 and GravesX
remains installed, respecting the separate shared-inventory compatibility gate.
No other server was restarted by this task. WorldView is absent and port 25912
remains closed. Mine reindex completed 588,544 cells / 24,351 indexed mineables.

At 01:15:26, native expiry logged two expired MAIL records. Exact disk readback
confirmed both old records became EXPIRED revision 3 with the inactivity reason,
and the recent gift remained MAIL revision 2. Current total: 1,149 DELIVERED,
two EXPIRED, one MAIL; two additional deliveries occurred since the earlier
1,150-record heap snapshot. No currency/native reward action was invoked for
expiry. History-label appearance remains unverified in a native client.

After more than three minutes of warmup, the all-thread allocation capture
ran 01:18:42–01:20:12, 90.041 seconds, zero players. Both comparison windows had
1,746 chunks, 7,096 block entities and 1,809 entities, with the same sampler
mode and byte interval. Tree references and disjoint weight totals validated.

| Semantic owner | Before, MiB/s | After, MiB/s |
| --- | ---: | ---: |
| ArcFarms | 13.64 | 12.25 |
| ArcExcellentCrates | 9.16 | 4.65 |
| ArcContracts | 6.08 | 2.27 |
| Citizens | 20.21 | 21.12 |
| Other | 27.57 | 26.81 |
| All threads | 76.67 | 67.10 |

The changed plugins together fell from 28.88 to 19.17 MiB/s (about 34%); overall
allocation fell 9.57 MiB/s (about 12.5%). The after window stayed at 20 TPS,
median ticks 7.32–7.35 ms and maximum 40.21 ms. These are sampled idle-window
results, not acceptance of busy-server behavior or a native FPS measurement.
The saved profile is `profile-2026-11-03_01.20.14.sparkprofile`, SHA-256
`dfc3739decc8ab7a0665a0f93fbc606931cffcb891cb573de7812df28d434558`.

At 01:22, the matching native histogram for PID 453800 measured 76,876,471 live
objects / 4,166,867,624 bytes (3.8807 GiB), 251.72 MiB / 4,575,623 objects below
the fresh before histogram. The before heap observation had one player and
the after observation was empty; restart/caches and coordinated ArcEvents
activation are additional covariates. Therefore the entire 251.72 MiB delta
must not be attributed exclusively to these fixes. Retainer-specific MAT
comparison is recorded separately below. The new private live HPROF is mode
600 under a mode-700 directory and remains on the server.

Further disjoint attribution of the original allocation window found metrics
6.43 MiB/s, casino 1.58, Origin 1.58 and EliteMobs 0.91; these are subsets of
Other and must not be added to it. Source review found existing dirty-flush
protection in metrics. Possible future improvements are reusing one daily
window across event kinds and avoiding repeated UTF-8 encoding during saves;
no ARC change was made during the other owner's 1.4.521 freeze. Citizens is
third-party and was not patched.


### Retainer-specific after comparison

The matching after dominator report confirms the structural changes rather
than relying on the restart's total heap delta:

| Named retainer | Before bytes | After bytes |
| --- | ---: | ---: |
| MineExpeditionWorld | 265,727,880 | 86,835,000 |
| OpeningLedger | 107,657,504 | 460,112 |
| EliteMobs RegionalBossEntity | 527,927,352 | 527,927,288 |
| EliteMobs CustomBossesConfigFields | 601,758,448 | 601,846,456 |

These are MAT 1.17 estimates for its assumed header layout, not physical native
bytes; do not add their differences to the native 251.72 MiB total delta.
There is one MineExpeditionWorld owner with three reserved scenes, not three
owner instances. Exact immutable index sizes remain 139,725 + 141,839 + 286,626
= 568,190 cells. No scene was evicted to obtain the reduction. OpeningLedger
now retains one full MAIL record and 1,151 compact terminal receipts. Native
histogram independently reports one OpeningRecord, so the completed full
records are not still retained by another owner. The old 568,190 worksite
RecordKey instances are absent; unrelated rescue-maze keys remain.

The native SnakeYAML class shallow total is 427,031,016 before and 425,529,144
after (about 406 MiB after), excluding associated java.lang.String/backing
buffers. It is not additive with the named configuration-owner retained sizes.
The approximately 91,083 EliteMobs regional holders are unchanged. These are
material remaining baseline owners under the no-third-party-JAR constraint.

Protected reports: `spawn-before_Query/pages/Query_Command2.csv` and
`spawn-after_AfterOwners/pages/Query_Command2.csv`; scene/index metadata in
`spawn-after_AfterSceneEntries/` and `spawn-after_AfterIndexAll/`, beneath the
same private diagnostic directory. MAT jobs
`3996f7c56fdc7536a9da47882eefabf3733d7cd5cfdcd130998ac02e84b0853e`,
`a870003a5a82621ecc293fe52341ddba60f7ad609b06c7aa089e5b3d2091b85f` and
`2b6068dd7b8224c3374604cbb195355efde567a77017d0dbe6b4b5501db3b74c`
completed successfully. No MAT process remains. The numeric private snapshot
was not uploaded to a public heap service.

Canonical economy/menu inventory and reusable retained-heap/RCNet guidance were
published in ruscrafting-ops `32831fee5448b62481d6837a71722c0415b16479`. The
installed profiling reference received only the same focused guidance; its
pre-existing local changes were preserved. Scoped diff checks passed. The
skills-wide architecture validator still reports the unrelated pre-existing
545-line develop-minecraft-plugins skill exceeding its 500-line budget; that
skill was not modified in this task.
