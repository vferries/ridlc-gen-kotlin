# conformance

## Responsibility

This module owns the pinned `ridl` release, the corpus, and every test of
docs/design.md §7 that needs the plugin from outside. It is a JVM test module.
The repository is licensed under the root [MIT License](../../LICENSE).

- `ridl-release` is the pin: one `driftsys/ridl` release tag. The `installRidl`
  task installs that release's `ridl` binary into `build/ridl` with the
  release's own `install.sh`; `RIDL_BIN=<path>` uses an installed `ridl`
  instead, for a machine with no network. The pin never resolves to a local
  checkout.
- `src/test/corpus/` holds one directory per corpus package; its README says
  where each comes from and what it exercises.
- `src/test/resources/probes/` holds hand-written probes, compiled with a
  package's generated code, for what the model-driven probe does not reach.
- `checkSchema`, run by `check`, fails when the schema vendored under
  `modules/ridlc-gen-kotlin/src/main/proto` differs from the pinned release's.

## Status

Pinned to `editor-v0.2.2`. The tests of stage K2a run: a request the pinned
`ridl` wrote parses, a request with an unknown key parses, a request nested
1,000 levels parses in process and through the installed script, a wrong schema
is one error diagnostic and exit 0, an unknown option is an error diagnostic,
unreadable input is exit 3, and the parity test compares `ridl build` with
`--plugin kotlin=<script>` to the script invoked directly, for every package
each build hands the plugin.

The tests of stage K2b: for every corpus package, the generated `Types.kt`
compiles with `kotlin-compile-testing` against `ridl-rt-kt` with warnings as
errors; and a probe written from the model, compiled with it, checks that every
constrained scalar accepts each bound and refuses one step outside it with the
right rule, that enums and enum sets read their declared members and no other,
and, for `kt-values`, that a struct checks its collections and inline fields.
Removing the float maximum check, counting UTF-16 units, or dropping an array
bound from the emitter each turns the probe red.
