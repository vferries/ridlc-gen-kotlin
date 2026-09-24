# ridl-rt-kt-coroutines

## Responsibility

This module is the `suspend` adapter over the polling face: a generic `await`
over the `Wakeable` port of [`ridl-rt-kt`](../ridl-rt-kt/README.md)
(docs/design.md §5, O-K3). It is a JVM library over `kotlinx-coroutines-core`.
The repository is licensed under the root [MIT License](../../LICENSE).

## Status

Stage K0: the Gradle module exists with its dependencies; the adapter is stage
K5.
