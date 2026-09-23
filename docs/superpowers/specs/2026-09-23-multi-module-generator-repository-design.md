# Multi-Module Generator Repository Design

## Purpose

`ridlc-gen-kotlin` is the repository home for a family of future RIDL code
generator modules. The initial scaffold establishes repository and module
conventions without implementing Kotlin, code generation, or a language runtime.

The repository name follows RIDL's plugin naming convention. It does not limit
the initial repository layout to one language module.

## Scope

The initial scaffold includes:

- Git-std configuration, hooks, Conventional Commit scopes, and release
  metadata;
- Prim formatting and linting configuration for connective-tissue files;
- a Just command surface for formatting, checking, testing, and repository
  validation;
- repository documentation and contribution rules;
- an explicit module layout and rules for adding future generator modules;
- a place for shared, language-neutral plugin-protocol material.

The initial scaffold excludes:

- Kotlin source code or Kotlin compiler configuration;
- Gradle, Maven, Kotlin serialization, or other language-specific build
  dependencies;
- Rust, Cargo, or copied RIDL compiler implementation;
- a codegen backend, plugin executable, or generated artifact;
- a claim that any future module is already supported by `ridl`.

## Repository layout

```text
ridlc-gen-kotlin/
├── docs/
│   └── superpowers/
│       └── specs/
├── modules/
│   ├── ridlc-gen-kotlin/
│   ├── ridl-rt-kt/
│   ├── ridl-rt-kt-loopback/
│   ├── ridl-rt-kt-coroutines/
│   └── conformance/
├── samples/
│   └── cabin/
├── shared/
│   └── README.md
├── .editorconfig
├── .git-std.toml
├── .gitignore
├── AGENTS.md
├── CONTRIBUTING.md
├── LICENSE
├── README.md
└── justfile
```

`modules/` contains the repository's planned modules:

| Module                          | Responsibility                                                   |
| ------------------------------- | ---------------------------------------------------------------- |
| `modules/ridlc-gen-kotlin`      | The `ridlc-gen-kotlin` plugin executable.                        |
| `modules/ridl-rt-kt`            | The Kotlin runtime contract.                                     |
| `modules/ridl-rt-kt-loopback`   | The in-process runtime used by tests.                            |
| `modules/ridl-rt-kt-coroutines` | The `suspend` adapter over the runtime contract.                 |
| `modules/conformance`           | The pinned RIDL release and the complete conformance test suite. |

Each module owns its implementation and tests. A module must document its
language, target, executable or library identity, input/output contract, and
toolchain when it is introduced.

Every module starts with its own `README.md`, even while it is only a
placeholder. The README states the module's responsibility, status, planned
public boundary, and the toolchain it will eventually require. The
`samples/cabin/README.md` follows the same rule and describes the sample's
runtime dependencies and demonstration purpose.

`samples/cabin` is the JVM demonstration application. It exercises the runtime
through `ridl-rt-kt-loopback` and is not itself a runtime module.

`shared/` is reserved for material genuinely shared by two or more modules. The
initial scaffold does not place protocol code there. A future shared component
must have at least two consumers before it is extracted.

## Tooling contract

The repository's public developer commands are Just recipes. The initial recipes
are:

- `just fmt` — format connective-tissue files with Prim;
- `just fmt-check` — verify formatting without writing;
- `just check` — run Prim's non-writing lint/check commands and repository
  validation;
- `just test` — run repository-level tests when modules exist, otherwise report
  that no module tests are configured;
- `just build` — run the complete scaffold gate;
- `just verify` — run commit linting followed by `just build`;
- `just bootstrap` — install or explain required Git-std, Prim, and Just setup.

The scaffold must not require a Kotlin, Rust, Gradle, Maven, or Cargo
installation to pass its repository-level checks.

## Module boundary

The repository is language-neutral at the root. The listed modules are the
planned Kotlin/JVM product surface, but their implementation and language
toolchains remain outside this initial scaffold. A module may choose a
language-specific build system inside its own directory, but the root Just
interface remains the integration point. Root checks must not silently compile
or test a module unless that module declares the corresponding recipe and
toolchain requirement.

The RIDL plugin contract is documented as an external compatibility target, not
implemented here initially. Future modules must consume the canonical
`ridl.codegen.v1` request and response contract defined by the RIDL repository
and must not fork that schema in this repository.

`modules/conformance` owns the pinned RIDL release used for compatibility checks
and the complete test suite that exercises the generator and runtime modules
against that release. The pinned release is a module input and must not be
inferred from the developer's current checkout.

## Success criteria

The scaffold is complete when:

1. a fresh checkout can run the documented root checks with only Git-std, Prim,
   and Just installed;
2. no Kotlin, Rust, Cargo, Gradle, Maven, or generated source is present;
3. a contributor can add a future module without changing the root repository
   conventions;
4. the repository's commits pass Git-std validation;
5. the documentation clearly distinguishes the empty scaffold from an
   implemented RIDL plugin.

## Non-goals

This change does not decide the Kotlin module's API, package names, build
system, serialization library, Android integration, generated-code shape, or
release versioning beyond the repository-level Git-std setup.
