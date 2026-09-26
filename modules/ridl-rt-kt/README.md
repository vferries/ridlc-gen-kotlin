# ridl-rt-kt

## Responsibility

This module is the Kotlin spelling of `ridl-rt`: the vocabulary types, the port
interfaces, the payload interface and the error types that generated code and a
runtime agree on, and nothing that runs (docs/design.md §3). It is a JVM library
with no dependency but the Kotlin standard library, in the packages
`ridl.rt.contract`, `ridl.rt.encoding`, `ridl.rt.error`, `ridl.rt.payload`,
`ridl.rt.port`, `ridl.rt.sample`, `ridl.rt.flatbuffers` and `ridl.rt.task`, one
per `ridl_rt` module. The repository is licensed under the root
[MIT License](../../LICENSE).

## Status

Stage K1a: the correspondence table of §3 is code, and `CorrespondenceTest` pins
one row per `ridl-rt` item of the pinned release: the Kotlin item exists, lives
in the package of its Rust module, has the Rust name, and has the Rust variants,
fields and methods under their Kotlin spellings.

Stage K1b adds `ridl.rt.flatbuffers`: `Reader`, the bounds-checked reads and
vtable walk a generated `verify` needs, and `Builder`, the back-to-front writer.
It is the spelling of `ridl_rt::flatbuffers`, whose free reading functions are
`Reader`'s methods over a `ByteBuffer`. The spike that decided it is
[`docs/k1b-flatbuffers-spike.md`](../../docs/k1b-flatbuffers-spike.md).

driftsys/ridlc-gen-kotlin#5 adds three items of ridl `main` ahead of the release
that carries them, and `CorrespondenceTest` rows them from `main`: the keyed
`port::Wakeable` and `port::Interest` and `error::Transport::Busy` (story
E11.16, c0fa57c), and `ridl.rt.task`, the spelling of `ridl_rt::task` `block_on`
and `noop_waker` (story E11.17, 3f2cfb3), with `Waker` standing for
`core::task::Waker`. `TaskTest` is `crates/ridl-rt/tests/task.rs`, case for
case.

## Where the code departs from docs/design.md

- **Errors are exceptions.** §3 spells the error types as `sealed interface`s
  that are thrown, and `Caller.ack` as a Kotlin `Result` carrying a `CallError`.
  Kotlin throws and carries in a `Result` only a `Throwable`, so every error
  type is a sealed class over `RidlError`, a `RuntimeException` that captures no
  stack trace; its data-less variants are `data object`s. `Contract` and
  `Transport` extend `CallError`, so a `when` over a call error names its two
  arms or every variant of both.
- **`Cause`, `Detection` and `Freshness` are sealed**, not `enum class`, because
  `Detected`, `InvalidValue` and `Stale` carry data as the Rust variants do.
  `Detection` is an error, so `Occurrence.payload` is a Kotlin `Result`.
- **`Payload<T, V>`** has a second type parameter, the view `verify` returns and
  `decode` takes, and an `encoding` property. Taking the view is how a value is
  decoded only from checked bytes, the guarantee Rust's `Ref` proof gives; `Ref`
  and `Encoded` have no Kotlin spelling.
- **`Command.require`, `Query.require` and `Query.ensure` return `Boolean`**
  where Rust returns `Result<(), ()>`.
- **`ConstraintViolation`** (§4) is Kotlin's own, with no Rust item.
- **`Wakeable` is keyed** (`wakeOn(what: Interest, waker: Waker)`), where §3
  gives it one unkeyed `onChange(callback): AutoCloseable` as Kotlin's own
  extension: `ridl-rt` now has the extension itself (ADR-0021 decision 13), and
  this is its spelling. A registration is not closed; a stored waker is woken at
  most once and cleared, and a later registration displaces it.
- **`Waker`** is Kotlin's own, the `fun interface` spelling of
  `core::task::Waker`: two wakers are the same task when they are the same
  object, which is `will_wake`. A Rust future is spelled `(Waker) -> T?`, a poll
  that answers `null` for `Pending`.
- **`blockOn(deadline, poll)`** takes the future last, so it can be a trailing
  lambda, and the deadline as a `TimeSource.Monotonic.ValueTimeMark`, the
  monotonic clock `std::time::Instant` is. The JVM always has what the Rust
  `std` feature adds, so `ridl.rt.task` is not optional.
- **`Transport.Busy` breaks an exhaustive `when`.** `Transport` is
  `#[non_exhaustive]` in Rust, so adding `Busy` breaks nothing there; a Kotlin
  sealed class has no such marker, and a `when` over `Transport` or `CallError`
  with no `else` must name it.
