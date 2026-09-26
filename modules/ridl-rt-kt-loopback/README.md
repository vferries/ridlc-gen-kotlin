# ridl-rt-kt-loopback

## Responsibility

This module is the in-process reference runtime every test runs over: every port
of [`ridl-rt-kt`](../ridl-rt-kt/README.md) over one in-memory store, a map and a
queue, no IO; the Kotlin spelling of `crates/ridl-loopback` (docs/design.md §3,
§6). It is a JVM library in the package `ridl.rt.loopback`, depending on
`ridl-rt-kt` alone. The repository is licensed under the root
[MIT License](../../LICENSE).

`Loopback` is the aggregate handle a generated face is built over: it implements
all twelve port interfaces by delegating to six role handles, `ReaderHandle`,
`WriterHandle`, `SourceHandle`, `SinkHandle`, `CallerHandle` and
`HandlerHandle`, which `split()` hands out and `reader()`, `writer()` and the
other factories add. `advance` drives the clock by hand, `provisionFixed`
supplies a `fixed`, and `failNextSettle` injects the one fault the runtime has.

## Status

Stage K1c. The port contract tests any runtime can run are the suite of
[`ridl-rt-kt-conformance`](../ridl-rt-kt-conformance/README.md), which
`ConformanceTest` runs over this runtime, all 41 (driftsys/ridlc-gen-kotlin#8).
`PortsTest` is what stays in `crates/ridl-loopback/tests/ports.rs` on ridl
`main`, the tests only this runtime can express, each for the reason that file
gives, plus the JVM-specific tests below.

driftsys/ridlc-gen-kotlin#5 adds the `Wakeable` of ridl `main` (story E11.16, as
c2543c2 left it). Every handle is `Wakeable`, and stores one waker per kind of
key it observes: `SourceHandle` one `Event` waker, `HandlerHandle` one `Claim`
waker, `CallerHandle` an `Outcome` waker with each call; any other kind, and
`Slot`, is woken at once. The aggregate routes each key to the handle that
observes it, and `CallerHandle` also carries `Clock`. Closing a handler returns
the claims it held and had not settled to the waiting calls, in send order, and
wakes the handlers that serve them (ADR-0021 decision 5). `WakeableTest` is the
"Waking" tests of `ports.rs` at c2543c2, under the same names and in the same
order.

## Where the code departs from the Rust loopback

- **Threading.** The Rust handles are `Send`, and the reader is also `Sync`,
  proved at compile time. Here every store access is under one monitor, so the
  reader handle is safe from any thread; the other five hold unsynchronized
  state of their own and are driven by one thread at a time, which is a rule of
  use, not something the compiler checks.
- **`SourceHandle` and `HandlerHandle` are `AutoCloseable`.** The Rust handles
  remove their queue and their waiter from the store on drop; the JVM has no
  drop, so `close()` does it, and a source never closed keeps receiving copies
  while the runtime lives.
- **`split()` cannot consume the aggregate**, so the aggregate's port methods
  throw `IllegalStateException` after it; the factories and the test controls
  still work.
- **Buffers.** An input buffer is read from its position to its limit and left
  where it was, as a borrowed slice is; the bytes are copied, so the caller may
  reuse the buffer. An output buffer is written from its position, which is
  advanced; on `ReadError.Short` nothing is written. `ridl-rt-kt`'s port
  contract now states both.
- **`Handler.settle`** takes a Kotlin `Result`; a failure that is not a
  `CallError` is refused with `IllegalArgumentException`.
- **Wakers run outside the monitor.** As in Rust, a store operation returns the
  wakers to wake and the handle wakes them after leaving the monitor. The JVM
  monitor is reentrant, so what this protects against is a waker that reads the
  runtime from another thread, not one that re-enters on its own.
