# ridl-rt-kt-loopback

## Responsibility

This module is the in-process runtime every test runs over: every port of
[`ridl-rt-kt`](../ridl-rt-kt/README.md) over a queue and a map, no IO; the
mirror of `crates/ridl-loopback` (docs/design.md §3, §6). It is a JVM library.
The repository is licensed under the root [MIT License](../../LICENSE).

## Status

Stage K0: the Gradle module exists and depends on `ridl-rt-kt`; the runtime is
stage K1c.
