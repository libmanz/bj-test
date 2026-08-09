# Derived Value Publisher

A small service that consumes a fast stream of per-instrument market data inputs, computes
a derived value per instrument, and publishes updates to a downstream consumer that is
slower than the input. slow consumer is achieved by using configuration parameters to inject
delay after processing each row to simulate real behaviors.


## How to use it
### Locally
After checking out project
```
mvn test
mvn exec:java -Dexec.args="src/test/resources/market_inputs.csv"
mvn exec:java -Dexec.args="src/test/resources/market_inputs.csv --input-delay-ms 1 --output-delay-ms 4 --jitter-pct 0.2 --seed 42"
mvn exec:java -Dexec.args="src/test/resources/market_inputs.csv --lock-free"
```
### GitHub actions
You can run solution using _GitHub Actions_ 
[![Execute DVP](../../actions/workflows/maven-exec-dvp.yml/badge.svg)](../../actions/workflows/maven-exec-dvp.yml) action. `Published_output.csv` and `audit.csv` will be avalable in action artifacts.  

CLI flags:

| flag                | meaning                                             | default                |
|----------------------|-----------------------------------------------------|------------------------|
| `<input.csv>`        | positional, required — path to the input stream     | —                      |
| `--output <path>`    | output CSV path                                     | `published_output.csv` |
| `--audit <path>`     | audit trail CSV path (rejected/conflated/incomplete events + summary) | `audit.csv`            |
| `--input-delay-ms N` | pause after each input row is consumed              | `1`                    |
| `--output-delay-ms N`| pause after each publish (models downstream/network latency) | `3`                 |
| `--jitter-pct P`     | +/- random jitter applied to each delay, e.g. `0.2` = ±20% | `0.0`                  |
| `--seed N`           | RNG seed, for reproducible jittered runs            | random                 |
| `--lock-free`        | use the lock-free buffer instead of the locked one  | off                    |

## Assumptions

The solution provided is based on the assumptions below.

**Header row**.
The first line of the input CSV is assumed to be a header and is skipped with no validation. Data are read by column position, not by header name.

**Input validation**.
A row is skipped/ignored, it is logged to Audit component, if any of below:
- row doesn't have exactly 4 comma-separated columns
- `timestamp_ms` doesn't parse as a `long`
- `instrument` is empty after trimming
- `input_type` isn't exactly one of the `base_rate`, `spread`, `adjustment` case-sensitive, all others are rejected.
- `value` can't be parsed as a `double` or is parsed as `NaN`/`Infinity`/`-Infinity` 

reason for skipping - bad records should not affect other instruments or running system order.

**Duplicate/out of order input events**.
Rows are processed in order they received from the downstream. There is no _duplicate_ records resolution logic.
Also, there is no validation if two events for the same instrument are out of order based on their timestamps. 
The same is true for any 2 consecutive events of unrelated instruments. 
No event re-odering mechanism has been implemented in the solution.    

**Derived Value availability**.
An instrument derived value is published for the first time when at least one record of each is type were received.
I.e. if two `base_rate` and one `adjustment` were received, but no `spread` update - the derived value would not be calculated
or published, once `spread` is received, then derived value will be provided. After that derived value update will be publsihed on each valid instrument event. 

**Derived Value event publishing**.
Derived values are published one by one. The main _conflated queue_ interface does not provide
functionality to retrieve all available at a time events. This intentional design choice for this exercise to ensure that consumer is consistently slower than provider.
Also, optimization of event publishing, i.e. batching, is out of scope at this time.        

**Rate mismatch**.
To simulate rate mismatch, an optional separate input/output configuration delays are provided.

**Events conflation**.
As client is not able to sustain source event rate, it is assumed that client accepts the fact not all events will be delivered.

**Output schema**,
```
publish_seq,publish_ts_ms,instrument,derived_value,source_ts_ms
```
In addition to the required derived values output schema the following fields are provided:
- `publish_seq` — publisher counter
- `publish_ts_ms` — wall-clock time the row was actually sent (when the simulated
  network/downstream delay completed)
- `source_ts_ms` — the `timestamp_ms` of the input row that last updated this instrument's
  state, i.e. the event-time the derived value reflects

`publish_ts_ms - source_ts_ms` represents event staleness

## What I would do if I have more time

1. Support event re-ordering for out of order events.
2. Support queue batch operation. 
3. Review classes naming conventions.
4. Add logging. 
5. Rework class structure and relationships.
   Change orchestration of DerivedValuePipeline by introducing Google Guice modules and DI.    
   Change ConflatingBuffer association to BufferDrainingSubscriber as publsih() parameter rather than instance variable and expose addListener method, rather association via constructor.
   Change DerivedValueComputer to registers with MarketDataPublisher for the market data events.

## Time Spent
1. **Design** 10%. Overall approach    
2. **Initial implementation** 30%. Design refinement, implementation was focused on LockedConflatingBuffer and basic reader and publisher functionality. 
3. **Refactoring** 30%. Improving solution design to allow for better classes testability and expansion for other event 
stream providers in the future, pub/sub interface extraction, unit tests.
4. **Audit Trail** 20%. Plugging in audit trail interface and implementation as per task requirement.
5. **Readme** 10%. 

## Design choices
1. The task is to support slow consumer, its imperative that we don't have unlimited in-memory storage.
So at some stage events are needed to be discarded even if we have large buffer.
2. As with publisher/consumer applications they both run in separate threads interacting via shared queue.
3. The queue design is the most important part of the solution. HashMap, RingBuffer were considered, however they don't satisfy ordering or fairness requirements.
4. If some events have to be dropped, it's important that at the time consumer is ready to 
pick up derived value from the queue the most up-to date value has to be provided, don't supply outdated
values when there is newer one and client never gets full set. That lead to the realization that only latest derived value is required.
We neded to preserve overall event ordering between different instruments and maintain only one value per
instrument. The LinkedHashMap satisfies this requirement. 
5. Since LinkedHashMap is not thread safe, if was wrapped into ConflatingBuffer interface with LockedConflatingBuffer implementation.

## AI use
Claude (Sonnet 5, via claude.ai website (not local CLI), free plan) was used as interactive build tool/partner/code reviewer.
It's a collaboration tool that allows to validate and improve solution design, receive feedback, generate code, unit tests and then review it independently.
The approach to use LinkedHasMap was identified prior to consulting AI agent, then it was confirmed in conversation with the agent.
I worked through design decisions (conflation strategy, fairness guarantee) in conversation before code was written. 
This was iterative process, i.e. the code was built incrementally, one feature or class or refactoring item at a time, with me reviewing each diff,
making local changes if needed, then running tests and solution.
First ConflatingBuffer was created along with publisher, all as one class. It was then split into interface,
concrete implementation and other classes, then the process repeated number of times refining implementation approach
and addition of unit tests. The classes, interface structures, orchestration pipeline were created based on my input.
The audit functionality requirements were picked up at later stage and implemented based on my input.
