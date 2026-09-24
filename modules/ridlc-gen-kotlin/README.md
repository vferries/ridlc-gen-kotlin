# ridlc-gen-kotlin

## Responsibility

This module owns the `ridlc-gen-kotlin` executable: it reads one
`ridl.codegen.v1` `CodegenRequest` on standard input and writes one
`CodegenResponse` on standard output, both canonical protobuf JSON
(docs/design.md §2). It is a JVM application built with Gradle, Kotlin 2 and
KotlinPoet, and its distribution is `bin/ridlc-gen-kotlin`,
`bin/ridlc-gen-kotlin.bat` and `lib/ridlc-gen-kotlin-all.jar`. The repository is
licensed under the root [MIT License](../../LICENSE).

## Status

Stages K2a, K2b and K2c: the reader, the launcher, the schema refusal, the
option parsing, `<kotlin-package path>/Types.kt`, the value objects of §4, and
`<kotlin-package path>/Codec.kt`, their FlatBuffers codec, both emitted with
KotlinPoet from the request's model. `Faces.kt` lands in K3a. `just dist` builds
the distribution, and
`ridl build --plugin kotlin=<path to bin/ridlc-gen-kotlin>` runs it.

A declaration the plugin cannot spell in Kotlin is refused with one error
diagnostic naming it, and every refused declaration of the package is reported
in the one response: a stream (O-K5), a reference that resolves to nothing, two
tuples spelling one name, an enum with no value, a union with no arm, and every
position the Rust codec emitter refuses — a type with no finite FlatBuffers
bound, a bare `string` or `bytes`, an optional array element or map part.

Tested against ridl `editor-v0.2.2` (`modules/conformance/ridl-release`).

## Where the code departs from docs/design.md

- **The recursion limit.** §2 step 1 configures the parser with
  `usingRecursionLimit(1000)`, but protobuf-java-util declares that method
  package-private. `com.google.protobuf.util.withRecursionLimit`, one function
  in the parser's own package, is the access to it.
- **The parser's stack.** A request nested 1,000 levels overflows the JVM's
  default 1 MiB thread stack inside the recursive parser, so `Wire.readRequest`
  parses on a thread with a 256 MiB stack. The stack is reserved, not committed.
- **`-classpath`, not `-jar`.** The Shadow start script runs the main class with
  the fat jar as the class path rather than `java -jar`; the effect is the same,
  and the script still ends in `exec "$JAVACMD" "$@"`.
- **`wire-encoding`** accepts `flatbuffers` alone until O-K1 is disposed.

### `Types.kt`

- **A string's length is its count of Unicode scalar values**, not its byte
  length as §4 writes: typl §4.4 says a `string [N]` bound counts scalar values
  "not bytes, which `bytes` counts", and the Rust backend counts `chars()`.
- **`step` is checked.** Integers have no step (TYPL-105). A float is valid at
  `min + n·step`, from 0 without a minimum, within a millionth of the step plus
  one ulp at the wire width, so a binary32 that crossed the wire still passes.
  The Rust backend checks no step (driftsys/ridl#469).
- **A range check refuses NaN** (`!(value >= min)`); the Rust one admits it.
- **A pattern is searched for**, as Rust's `Regex::is_match` does, not matched
  whole: the pattern's own `^` and `$` decide.
- **Every integer is a `Long` and every float a `Double`**, as every Rust
  backing is `i64` or `f64`; the declared widths are the codec's.
- **An enum's discriminant is a `Long`**, not the `Int` of §4, because the
  model's is an `int64`.
- **An enum set has a private constructor** and `of`, which refuses a bit no
  member declares with the rule `Variant`, as the Rust `TryFrom` does.
- **A bytes scalar is a final class**, not a value class: a value class cannot
  compare a `ByteArray` by content. It holds a private copy.
- **O-K4 is decided as §4 has it**: an array's and a map's bounds are checked
  when the owning struct or tuple is built, with no value class per bounded
  collection. The same `init` checks an inline scalar's constraints, which the
  Rust backend does not check at all. Collections and bytes are copied in, and
  bytes copied out, so a constructed value stays valid.
- **Constants** are properties of `object Constants`: a `const val` for a
  primitive or a regex, a `val` holding the value object for a named scalar. A
  bytes constant has no spelling and is left out, as in the Rust backend.
- **A reference into another package** is spelled in that package's dotted name,
  the `kotlin-package` default (O-K2): the model does not say what option the
  other package was generated with.

### `Codec.kt`

`Codec.kt` is the Rust codec emitter of the pinned release spelled in Kotlin,
over `ridl-rt-kt`'s `ridl.rt.flatbuffers`: one `<Type>Codec` object implementing
`Payload` per root of the package's FlatBuffers projection, and `internal`
encode, verify and decode helpers per table-shaped type. It lays a table out as
the Rust codec does — declaration order, each field at its own alignment — and
pushes children in the same order, so it writes the same bytes, and verifies in
the same order, so it reaches the same verdict. The conformance module holds it
to that over 10,266 buffers (`CodecTest`).

- **O-K1 is taken as option A**, pending its disposition
  ([`docs/k1b-flatbuffers-spike.md`](../../docs/k1b-flatbuffers-spike.md)), and
  **D-K5's second half is not followed**: the codec is written over
  `ridl.rt.flatbuffers`, as the Rust codec is over `ridl_rt::flatbuffers`, not
  over the classes `flatc --kotlin` generates, so a consumer's build needs no
  `flatc`.
- **`verify` refuses three things the Rust verifier accepts**: a float off its
  `step`, a NaN, and an inline scalar outside its constraints. `decode` builds
  value objects, whose constructors refuse all three, and must never throw.
- **A map decodes to a `Map`**, so two entries with one key keep the last, where
  the Rust codec keeps a `Vec` of pairs.
- **A vector of booleans** is one byte per element; the Rust codec emitter
  generates code for it that does not compile (`bool` has no `to_le_bytes`).
- **The helpers are `internal`**, and a codec reaches another package's helpers
  by name: the packages of one `ridl build` are compiled into one module, as the
  Rust backend writes them into one crate.
- **An exempt root** — one the projection cannot bound because it reaches a type
  it cannot judge — gets no codec, and the file's header names it, as the Rust
  codec writes a `__RIDL_FB_NO_CODEC_*` note.
