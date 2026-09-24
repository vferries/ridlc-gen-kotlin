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

Stage K2a: the reader, the launcher, the schema refusal and the option parsing.
A request that reads cleanly is answered with no file; the generators of §4 and
§5 land in K2b to K3b. `just dist` builds the distribution, and
`ridl build --plugin kotlin=<path to bin/ridlc-gen-kotlin>` runs it.

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
