# ridl-rt-kt-loopback

## Responsibility

This module is the in-process reference runtime every test runs over: every port
of [`ridl-rt-kt`](../ridl-rt-kt/README.md) over one in-memory store, a map and a
queue, no IO; the Kotlin spelling of `crates/ridl-loopback` (docs/design.md §3,
§6). It is a JVM library in the package `ridl.rt.loopback`, depending on
`ridl-rt-kt` alone. The repository is licensed under the root
[MIT License](../../LICENSE).

`Loopback` is the aggregate handle a generated face is built over: it implements
all eleven port interfaces by delegating to six role handles, `ReaderHandle`,
`WriterHandle`, `SourceHandle`, `SinkHandle`, `CallerHandle` and
`HandlerHandle`, which `split()` hands out and `reader()`, `writer()` and the
other factories add. `advance` drives the clock by hand, `provisionFixed`
supplies a `fixed`, and `failNextSettle` injects the one fault the runtime has.

## Status

Stage K1c. `PortsTest` is `crates/ridl-loopback/tests/ports.rs` of the pinned
release, test for test and name for name, plus the JVM-specific tests below.

## Where the code departs from the Rust loopback

- **Threading.** The Rust handles are `Send`, and the reader is also `Sync`,
  proved at compile time. Here every store access is under one monitor, so the
  reader handle is safe from any thread; the other five hold unsynchronized
  state of their own and are driven by one thread at a time, which is a rule of
  use, not something the compiler checks.
- **`SourceHandle` is `AutoCloseable`.** The Rust handle removes its queue from
  the store on drop; the JVM has no drop, so `close()` does it, and a source
  never closed keeps receiving copies while the runtime lives.
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
- **`Wakeable`.** The loopback presents the extension of docs/design.md §3,
  which the Rust one has no counterpart for: `onChange` callbacks run after
  every commit, raise, send and settlement, outside the store's monitor. It is
  what the coroutine adapter of stage K5 is tested over.
