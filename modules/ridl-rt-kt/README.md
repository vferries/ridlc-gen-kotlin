# ridl-rt-kt

## Responsibility

This module is the Kotlin spelling of `ridl-rt`: the vocabulary types, the port
interfaces, the payload interface and the error types that generated code and a
runtime agree on, and nothing that runs (docs/design.md §3). It is a JVM library
with no dependency but the Kotlin standard library, in the packages
`ridl.rt.contract`, `ridl.rt.encoding`, `ridl.rt.error`, `ridl.rt.payload`,
`ridl.rt.port`, `ridl.rt.sample` and `ridl.rt.flatbuffers`, one per `ridl_rt`
module. The repository is licensed under the root [MIT License](../../LICENSE).

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
- **`ConstraintViolation`** (§4) and **`Wakeable`** (§3) are Kotlin's own, with
  no Rust item.
