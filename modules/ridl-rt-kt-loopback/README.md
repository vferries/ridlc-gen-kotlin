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

Stage K1c. `PortsTest` is `crates/ridl-loopback/tests/ports.rs` of the pinned
release, test for test and name for name, plus the JVM-specific tests below.

driftsys/ridlc-gen-kotlin#5 adds the keyed `Wakeable` of ridl `main` (story
E11.16), on the three handles a task waits on, each for the keys of its role:
`SourceHandle` for `Interest.Event`, `CallerHandle` for `Interest.Outcome` and
`Interest.Slot`, `HandlerHandle` for `Interest.Claim`; the aggregate routes each
key to its handle. `CallerHandle` also carries `Clock`. `WakeableTest` is the
tests E11.16 added to `ports.rs`, under the same names, and three of its own: a
forgotten call's waiter, the aggregate's routing, and the caller's clock.

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
