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
- `src/test/corpus/` holds one directory per corpus package, copied from the
  pinned release's `examples/`.
- `checkSchema`, run by `check`, fails when the schema vendored under
  `modules/ridlc-gen-kotlin/src/main/proto` differs from the pinned release's.

## Status

Pinned to `editor-v0.2.2`. The tests of stage K2a run: a request the pinned
`ridl` wrote parses, a request with an unknown key parses, a request nested
1,000 levels parses in process and through the installed script, a wrong schema
is one error diagnostic and exit 0, an unknown option is an error diagnostic,
unreadable input is exit 3, and the parity test compares `ridl build` with
`--plugin kotlin=<script>` to the script invoked directly.
