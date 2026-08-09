# Derived Value Publisher

## Project Structure

```
src/main/java/dvp/
  DerivedUpdate.java              a computed derived value, tagged with its source event time
  InstrumentState.java            per-instrument running state (3 inputs -> completeness ->
                                   derive() -> missingInputsDescription() for audit reporting)
  RowParser.java                  String -> ParsedRow | (rejection-reason + instrument,
                                   when readable) function -- the instrument attribution
                                   feeds the audit trail's REJECTED events
  ConflatingBuffer.java           the interface both buffer strategies implement; put()
                                   returns the pending value it just overwrote (or null),
                                   which is how conflation gets detected and counted
  LockedConflatingBuffer.java     ReentrantLock + Condition over a LinkedHashMap; put()'s
                                   overwrite-return is exact (Map.put() gives it for free)
  LockFreeConflatingBuffer.java   ConcurrentHashMap + ConcurrentLinkedQueue + AtomicReference/CAS;
                                   put()'s overwrite-return is best-effort under real
                                   contention, same race window as the duplicate-send fix
  DelayUtil.java                  shared sleep-with-jitter pacing helper
  AuditLog.java                   interface: logRejected/logConflated/logIncompleteAtShutdown/
                                   logSummary/logGlobalSummary/close() 
  CsvAuditLog.java                pure I/O: streams REJECTED/CONFLATED/INCOMPLETE_AT_SHUTDOWN
                                   events to disk immediately, writes SUMMARY rows once at
                                   the end -- no decisions live here, only DerivedValueComputer
                                   decides what's audit-worthy
  MarketDataPublisher.java        interface: publish(DerivedValueComputer) -- the computer
                                   to publish through is passed per call, not injected at
                                   construction; any data source
  DerivedValueSubscriber.java     interface: subscribe() -- buffer is injected at construction;
                                   pulls from the buffer and dispatches to a listener;
                                   BufferDrainingSubscriber is its sole implementation
  DerivedValueListener.java       interface: onUpdate(DerivedUpdate), onComplete() -- any
                                   downstream sink, no buffer knowledge at all
  BufferDrainingSubscriber.java   the generic DerivedValueSubscriber: buffer + listener +
                                   pacing settings all injected via constructor; pulls from
                                   the buffer, dispatches each update to the listener, owns
                                   the output-side pacing (delay/jitter); no audit knowledge
  DerivedValueComputer.java       owns per-instrument state, the publishability decision,
                                   AND the audit trail (buffer + AuditLog both injected via
                                   constructor); state and counters live together in one
                                   InstrumentRecord per instrument, keyed by a single map --
                                   so an instrument touched only by rejections still gets
                                   flagged INCOMPLETE_AT_SHUTDOWN, not just partially-complete
                                   ones; bounded by instrument cardinality, not event volume;
                                   markComplete() scans for incomplete instruments and writes
                                   the final summary
  CsvMarketDataPublisher.java     purely mechanical: reads the file, parses each line, and
                                   delegates to whichever DerivedValueComputer is passed to
                                   publish()
  CsvDerivedValueListener.java    purely mechanical: writes one CSV row per onUpdate() call,
                                   opens/writes header at construction, closes on onComplete()                                   
  Config.java                     immutable run settings, built via Config.builder(inputPath)...
  DerivedValuePipeline.java       orchestrator: runs a publisher + subscriber on separate
                                   threads and joins them; buffer never flows through this
                                   class, but it does hold a DerivedValueComputer reference
                                   purely to forward into publisher.publish(computer) 
  Main.java                       CLI + composition root: args -> Config -> the ONLY place
                                   the buffer and the real CsvAuditLog are created and
                                   explicitly wired into the computer/publisher and
                                   subscriber/listener

src/test/java/dvp/
  RowParserTest.java                    one case per "rough edge" listed above, plus
                                         instrument-attribution checks for the audit trail
  InstrumentStateTest.java              completeness / derivation / overwrite semantics
  DerivedValueComputerTest.java         counters, conflation/rejection/incomplete audit
                                         events, and the shutdown summary 
                                         markComplete() closes it
  ConfigTest.java                       builder defaults, overrides (incl. auditPath),
                                         required-field check
  ConflatingBufferContractTest.java     the same behavioral contract run against BOTH buffer
                                         implementations via @ParameterizedTest, including
                                         put()'s overwrite-return semantics
  DerivedValuePipelineTest.java         orchestration itself, via fake publisher/subscriber
                                         implementations -- needs a real (unused-by-the-fake)
                                         DerivedValueComputer to satisfy the pipeline's
                                         constructor, but stays fully in-memory via
                                         NoOpAuditLog rather than a real CsvAuditLog
  NoOpAuditLog.java                     test-only no-op AuditLog implementation, deliberately
                                         NOT in src/main -- see its own Javadoc for why
  CsvMarketDataPublisherTest.java       real temp-file parsing/skip/conflation behavior;
                                         also uses NoOpAuditLog, since these tests care about
                                         CSV mechanics, not audit content
  CsvDerivedValueListenerTest.java      real temp-file output formatting/sequencing -- no
                                         buffer, no subscriber, no threading
  BufferDrainingSubscriberTest.java     pull-and-dispatch behavior via a fake listener -- no
                                         file I/O, no threading
  LockFreeConflatingBufferStressTest.java  real concurrent threads at volume, targeting
                                         the duplicate-delivery race directly (@RepeatedTest)

src/test/resources/
  market_inputs.csv               sample input 
```

**Pluggable seams.** `MarketDataPublisher` (`publish(DerivedValueComputer)`),
`DerivedValueSubscriber` (`subscribe()`), and `DerivedValueListener`
(`onUpdate(DerivedUpdate)`) are the three seams. `ConflatingBuffer` still never flows through
`DerivedValuePipeline` — it's injected via constructor into whatever needs it
(`DerivedValueComputer` on the publish side, `BufferDrainingSubscriber` on the subscribe
side). `DerivedValuePipeline` does, however, hold a `DerivedValueComputer` reference, purely
to forward it into `publisher.publish(computer)` — a deliberate choice: `publish()` alone
reads like a bare lifecycle signal (start/run), while `publish(computer)` makes explicit what
actually happens each call — rows flow through that computer as they're read. All the actual 
wiring happens in `Main.main`, the composition root: it builds the `ConflatingBuffer` and a 
`CsvAuditLog`, builds a `DerivedValueComputer` around both, builds `CsvMarketDataPublisher`, 
wraps a `CsvDerivedValueListener` inside a `BufferDrainingSubscriber` (buffer injected there
too), and wires the publisher, subscriber, and computer into a `DerivedValuePipeline`. 

## Concurrency design

**Two threads.** A `CsvMarketDataPublisher` (producer) streams
the CSV row by row, sleeping `--input-delay-ms` (± jitter) after each row. A
`BufferDrainingSubscriber` (consumer) drains the buffer and dispatches each update to a
`DerivedValueListener` (e.g. `CsvDerivedValueListener`), sleeping `--output-delay-ms`
(± jitter) after each dispatch. The two rates are genuinely decoupled — one thread's pacing
never blocks the other's.

**Conflation.** The buffer holds at most one pending (unsent) value per
instrument — a new update to an already-dirty instrument overwrites its pending value rather
than queueing a second entry. If the input outpaces the output, intermediate values are coalesced away and only the latest
state per instrument is ever sent.

**Fairness.** A high-update-rate instrument must not crowd out a rarely-updated one. Both
buffer implementations guarantee: an instrument's position in the send order is fixed the
moment it *first* goes dirty since its last send — not refreshed by subsequent updates, and
not something a busy instrument can jump by updating again.
- In `LockedConflatingBuffer`, this falls directly out of `LinkedHashMap` semantics: `put()`
  on an existing key updates the value in place without moving its iteration position
  (true because `accessOrder=false`, the default).
- In `LockFreeConflatingBuffer`, the same guarantee is built explicitly: an
  `AtomicBoolean queued` CAS flag per instrument prevents a second queue entry while one is
  already pending, and `ConcurrentLinkedQueue` preserves first-in order.

**Two implementations of the same contract, on purpose.** `LockedConflatingBuffer` is what
would actually be shipped for this workload (one producer, one consumer — contention is
essentially zero, so the lock costs nothing and buys simplicity). `LockFreeConflatingBuffer`
exists to demonstrate the alternative and its real trade-off: without a mutex, a narrow race
between the consumer clearing its "queued" flag and reading the value can cause a
redundant re-enqueue. Left unguarded, that shows up as an occasional duplicate send of an
unchanged value — never a lost update, but a real artifact. It's fixed with a per-slot
monotonically increasing version number (written atomically alongside the value by the
single producer, compared against "last version actually sent" — tracked only by the single
consumer thread, so it needs no synchronization of its own) so a redundant re-enqueue is
detected and silently skipped instead of re-published.

## Auditability

The brief specifically asks how the boundary is handled: what arrived, what was published,
what was rejected, and how an operator would audit it later. This is addressed by an audit
trail (`--audit`, default `audit.csv`) that `DerivedValueComputer` owns end to end — it's the
one class that sees every input row, every publish attempt, and every conflation, so it's
also the one place that decides what's audit-worthy.

**Two categories of "never published," logged separately.** A row can fail to become a
published value for two structurally different reasons, and collapsing them into one
"discarded" count would hide the more interesting one:
- **`REJECTED`** — bad data. A row that failed `RowParser` validation. Attributed to a
  specific instrument when the instrument column was readable (most rejection cases); rows
  rejected before that point (e.g. wrong column count) count only toward a global
  `unattributed_rejections` figure in the final summary.
- **`CONFLATED`** — good data, lost to design, not a bug. A row that became a fully valid
  derived value, was placed in the buffer... and was then overwritten by a newer value for
  the same instrument before ever being taken by the subscriber. This is the conflation
  design working exactly as intended, but from an audit standpoint it's indistinguishable
  from silent data loss unless logged explicitly — which is why it gets its own event type
  rather than being folded into "rejected."

A third, quieter case: **`INCOMPLETE_AT_SHUTDOWN`** — an instrument that received some
inputs but never all three (e.g. `base_rate` and `spread` arrived, `adjustment` never did
before the stream ended). Not malformed, not conflated, just permanently stuck incomplete.
Logged once per such instrument when the producer signals it's done.

**What's *not* re-logged as its own event, deliberately.** The input CSV already is the
record of what arrived, for every well-formed row; the output CSV already is the record of
what was published. Re-emitting every valid row or every successful publish into the audit
trail as well would just be duplicate noise on top of files that already serve that purpose.

**`delivered` is inferred, not tracked as its own counter.** `ConflatingBuffer.put()`
returns the pending value it just overwrote, or `null` if this was a fresh entry (a new
instrument, or one re-dirtying after its prior value was already sent — that's not a
conflation). Since every buffered value is eventually either overwritten while pending
(conflated) or drained by the subscriber (delivered) — there's no third outcome —
`delivered = publishAttempts - conflated` falls straight out of that accounting, without
`BufferDrainingSubscriber` needing to know audit exists at all or report a count back.

**Exact for the locked buffer, best-effort for the lock-free one.** `LockedConflatingBuffer`
gets its overwritten-value return essentially for free (`Map.put()` already returns the
previous value under the lock), so its conflation count is exact. `LockFreeConflatingBuffer`
shares the same narrow race window as its duplicate-suppression fix (see Concurrency design
above) — under real concurrent contention a small number of conflations could theoretically
go uncounted. Its conflation count should be treated as a lower bound, not an exact figure;
this is a direct, honest consequence of choosing lock-free over locked, not a separate bug.

**Memory shape.** Individual events are written and flushed to disk immediately as they
happen (`CsvAuditLog`, same pure-I/O pattern as `CsvDerivedValueListener`) — never buffered
in memory, so an unbounded input stream doesn't grow audit memory usage. Per-instrument
counters do live in memory (`DerivedValueComputer`'s `instruments` map, one `InstrumentRecord`
per instrument), but that's bounded by *instrument cardinality*, not event volume — a stream
with 10 instruments and 50 million
updates holds 10 counter entries either way, riding on the same map that was already
tracking `InstrumentState` regardless of whether auditing existed.

**Summary rows, written once, from memory.** At shutdown (`markComplete()`, called exactly
once when the producer signals it's done), a `SUMMARY` row per instrument
(`received`, `publish_attempts`, `conflated`, `delivered`, `completed`) plus one global
summary row (`unattributed_rejections`) are written from the in-memory counters — not by
re-reading the event log back and re-aggregating it. That's a deliberate simplicity choice:
re-deriving the summary from the log itself would be more "the log is ground truth," but
only earns its complexity if this needed to survive a crash mid-run and reconstruct state,
which isn't a requirement here.
