# Repository instructions

This repository is the language-neutral home for the planned RIDL generator and
runtime modules. The scaffold contains no Kotlin, Rust, or other language
implementation, compiler configuration, runtime code, generated source, or
generated artifacts.

Use the root `justfile` as the command surface for repository work. Run root
formatting, checking, testing, building, and verification commands through Just
rather than invoking a module toolchain from the repository root.

Keep the root repository policy independent of any module language or build
system. A future module may define its own toolchain inside its directory, but
must preserve the root checks and documentation conventions.
