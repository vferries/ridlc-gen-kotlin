# ridlc-gen-kotlin

The RIDL Kotlin plugin and its runtime library: `ridl build --plugin kotlin`
runs `ridlc-gen-kotlin`, which reads a package's lowered model and generates
Kotlin value objects, codecs and faces over `ridl-rt-kt`.

- [`ridlc-gen-kotlin`](modules/ridlc-gen-kotlin/README.md) — the generator
  plugin executable.
- [`ridl-rt-kt`](modules/ridl-rt-kt/README.md) — the Kotlin runtime contract,
  the spelling of `ridl-rt`.
- [`ridl-rt-kt-loopback`](modules/ridl-rt-kt-loopback/README.md) — the
  in-process runtime the tests run over.
- [`ridl-rt-kt-coroutines`](modules/ridl-rt-kt-coroutines/README.md) — the
  coroutine adapter over the runtime contract.
- [`conformance`](modules/conformance/README.md) — the pinned `ridl` release,
  the corpus, and the tests that run the plugin from outside.
- [`samples/cabin`](samples/cabin/README.md) — the JVM demonstration.

## Design

The design of the plugin, the runtime contract, the value objects, the codec and
the face is [`docs/design.md`](docs/design.md). It is the same text as
`docs/wip/2026-09-23-kotlin-plugin-design.md` in
[driftsys/ridl](https://github.com/driftsys/ridl), the toolchain repository
whose records it rests on; a change to one is made to both.

## Status

Stages K0, K1a, K1b, K1c, K2a, K2b and K2c of the design's §8: the Gradle build
and CI, the `ridl-rt-kt` correspondence table as code, the FlatBuffers verifier
spike ([`docs/k1b-flatbuffers-spike.md`](docs/k1b-flatbuffers-spike.md), for the
disposition of O-K1), the in-process loopback runtime, the plugin's reader and
launcher, and the value objects the plugin generates into `Types.kt`. Each
module README states its own stage and where its code departs from the design.

## Building

A JDK 17 or later, [Just](https://just.systems), Prim and Git-std.
`just
bootstrap` checks for them.

```sh
just build     # formatting, repository checks, every Gradle check, assemble
just test      # the Gradle tests alone
just dist      # the plugin distribution under modules/ridlc-gen-kotlin/build
```

The conformance tests download the pinned `ridl` release on first run; set
`RIDL_BIN` to an installed `ridl` to run them offline.

The repository is licensed under the root [MIT License](LICENSE). Module READMEs
refer to this license rather than adding another license file.
