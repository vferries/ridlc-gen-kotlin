# Multi-Module Generator Repository Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the language-neutral `ridlc-gen-kotlin` repository scaffold
with Git-std, Prim, Just, module READMEs, and structural checks, without Kotlin
or runtime implementation.

**Architecture:** The root repository owns policy and the Just integration
surface. Five module directories and one JVM sample directory begin as
documented placeholders; each owns a README that defines its future boundary.
The root checks validate repository content only and do not require a language
compiler.

**Tech Stack:** Git, Git-std, Prim, Just, Markdown, POSIX shell; no Rust, Cargo,
Kotlin, Gradle, Maven, or generated source.

**Spec:**
`docs/superpowers/specs/2026-09-23-multi-module-generator-repository-design.md`

## Global Constraints

- The repository must pass root checks with Git-std, Prim, and Just only.
- No Kotlin source, Kotlin compiler configuration, Gradle, Maven, Rust, Cargo,
  or generated source may be added.
- Root commands are exposed through `just` recipes.
- `modules/conformance` documents a pinned RIDL release as an explicit future
  input; it must not inspect the developer's current RIDL checkout.
- Every module and `samples/cabin` has its own README from the first scaffold
  commit.
- The RIDL `ridl.codegen.v1` contract is an external compatibility target and is
  not redefined or implemented in this scaffold.
- Commits use Git-std Conventional Commit types and scopes.

## Review Focus

- A clean checkout must run repository checks without a language compiler — pin
  with the `just check` and `just build` smoke tests in Task 3.
- A future contributor must be able to identify each module's responsibility and
  status without opening implementation files — pin with the README structure
  checks in Task 2.
- Root validation must not accidentally discover or compile placeholder
  directories — pin with the module-layout check in Task 3.
- The conformance module must preserve the distinction between a pinned RIDL
  release and a local checkout — pin with its README content check in Task 2.
- Formatting and commit policy must remain centralized in Prim, Just, and
  Git-std — pin with `just fmt-check`, `just check`, and `just lint-commits` in
  Task 3.

### Task 1: Create repository policy and metadata

**Files:**

- Create: `.editorconfig`
- Create: `.gitignore`
- Create: `.git-std.toml`
- Create: `AGENTS.md`
- Create: `CONTRIBUTING.md`
- Create: `LICENSE`
- Create: `README.md`

**Interfaces:**

- Produces the root repository policy consumed by all later module and Just
  tasks.
- Git-std scopes are `repo`, `docs`, `conformance`, `ridlc-gen-kotlin`,
  `ridl-rt-kt`, `ridl-rt-kt-loopback`, `ridl-rt-kt-coroutines`, `samples`, and
  `ci`.

- [ ] **Step 1: Write the root README and policy files**

  `README.md` must identify the repository as a language-neutral home for the
  five planned modules and `samples/cabin`, state that the scaffold contains no
  implementation, and link to each module README. `AGENTS.md` must state the
  same no-language-implementation constraint and require Just for root commands.
  `CONTRIBUTING.md` must document Git-std commits, Prim formatting, and the
  `just verify` gate. `.editorconfig` must set UTF-8, LF, final newline, and
  two-space Markdown indentation. `.gitignore` must exclude only generated and
  local files such as `.DS_Store`, `.idea/`, `.gradle/`, `build/`, and `out/`;
  it must not hide source or module README files.

- [ ] **Step 2: Configure Git-std**

  Create `.git-std.toml` with strict Conventional Commits, semver scheme, and
  the explicit scopes listed above. Keep the configuration independent of any
  language workspace so adding a module cannot silently change scope discovery.

- [ ] **Step 3: Add the repository license**

  Add the repository's approved license text to `LICENSE`. The root README and
  each future module README must refer to the root license rather than copying a
  second license file.

- [ ] **Step 4: Format and inspect policy files**

  Run:

  ```bash
  prim fmt .
  git diff --check
  git status --short
  ```

  Expected: connective-tissue files are formatted, no whitespace errors are
  reported, and only the intended root files are present.

- [ ] **Step 5: Commit the policy layer**

  ```bash
  git add .editorconfig .gitignore .git-std.toml AGENTS.md CONTRIBUTING.md LICENSE README.md
  git commit -m "chore(repo): establish repository policy"
  ```

### Task 2: Add module and sample placeholders

**Files:**

- Create: `modules/ridlc-gen-kotlin/README.md`
- Create: `modules/ridl-rt-kt/README.md`
- Create: `modules/ridl-rt-kt-loopback/README.md`
- Create: `modules/ridl-rt-kt-coroutines/README.md`
- Create: `modules/conformance/README.md`
- Create: `samples/cabin/README.md`
- Create: `shared/README.md`

**Interfaces:**

- Each README is the public placeholder contract for its directory.
- `modules/conformance/README.md` states that the RIDL release pin is not yet
  selected in this scaffold and must later be explicit; it must not resolve a
  local RIDL checkout.

- [ ] **Step 1: Write the plugin README**

  `modules/ridlc-gen-kotlin/README.md` must state that the future module owns
  the `ridlc-gen-kotlin` executable, consumes the external `ridl.codegen.v1`
  contract, and currently has no implementation or toolchain.

- [ ] **Step 2: Write runtime README files**

  `modules/ridl-rt-kt/README.md` must define the future runtime contract.
  `modules/ridl-rt-kt-loopback/README.md` must define the in-process runtime
  used by tests and identify `ridl-rt-kt` as its dependency.
  `modules/ridl-rt-kt-coroutines/README.md` must define the future `suspend`
  adapter over `ridl-rt-kt` and state that it is not implemented.

- [ ] **Step 3: Write conformance and sample README files**

  `modules/conformance/README.md` must say that it owns the pinned RIDL release
  and all cross-module tests, and that the pin is explicit rather than inferred
  from a local checkout. `samples/cabin/README.md` must say that it is a JVM
  demonstration over `ridl-rt-kt-loopback`, with no sample source yet.

- [ ] **Step 4: Document the shared directory**

  `shared/README.md` must reserve the directory for material with at least two
  real consumers and prohibit moving protocol definitions there before that
  threshold is met.

- [ ] **Step 5: Add README structural checks to the plan's test target**

  The root check introduced in Task 3 must verify that all seven README paths
  exist, contain a responsibility statement, and contain a status statement.

- [ ] **Step 6: Format and commit the placeholders**

  ```bash
  prim fmt .
  git diff --check
  git add modules samples shared
  git commit -m "docs(repo): describe generator and runtime modules"
  ```

### Task 3: Create the Just command surface and scaffold checks

**Files:**

- Create: `justfile`
- Create: `scripts/check-scaffold.sh`

**Interfaces:**

- `just fmt` formats connective tissue with `prim fmt .`.
- `just fmt-check` runs `prim fmt --check .`.
- `just check` runs `prim lint .` and `scripts/check-scaffold.sh`.
- `just test` runs `scripts/check-scaffold.sh` and reports the repository has no
  implementation tests yet.
- `just build` runs `fmt-check` and `check`.
- `just lint-commits base="main"` runs
  `git std lint --range "origin/$base..HEAD"` with the repository's configured
  scopes.
- `just verify` runs `lint-commits` followed by `build`.
- `just bootstrap` checks for `git-std`, `prim`, and `just` and prints the
  missing installation names without invoking a language toolchain.

- [ ] **Step 1: Write the failing scaffold-check script**

  `scripts/check-scaffold.sh` must use `set -euo pipefail`, resolve the
  repository root from the script path, and fail if any required README or root
  policy file is missing. It must also fail if tracked files match `*.kt`,
  `*.java`, `*.rs`, `Cargo.toml`, `build.gradle*`, `pom.xml`, or `gradlew`. It
  must verify that each module README contains `Status:` and `Responsibility:`
  headings.

- [ ] **Step 2: Run the failing script**

  Run `bash scripts/check-scaffold.sh` after creating the script but before
  creating all expected files. Expected: failure naming the first missing
  required path, proving the check detects an incomplete scaffold.

- [ ] **Step 3: Add the Just recipes**

  Implement the recipes and dependencies exactly as listed in the Interfaces
  section. The `test` recipe must not invoke Kotlin, Rust, Gradle, Maven, or
  Cargo. The `build` recipe must be deterministic on a clean checkout.

- [ ] **Step 4: Run the scaffold gate**

  ```bash
  just fmt-check
  just check
  just test
  just build
  ```

  Expected: every recipe exits zero, the structural script reports all module
  and sample READMEs, and no language compiler is invoked.

- [ ] **Step 5: Commit the command surface**

  ```bash
  git add justfile scripts/check-scaffold.sh
  git commit -m "ci(repo): add language-neutral scaffold checks"
  ```

### Task 4: Install hooks and verify the clean repository

**Files:**

- Modify: `.git/hooks/` through the Git-std installation command only

**Interfaces:**

- A fresh checkout can run `just verify` after the three required tools are
  installed.

- [ ] **Step 1: Install Git-std hooks**

  Run `git std install` and confirm the hooks are installed without adding
  generated files to the repository.

- [ ] **Step 2: Run commit linting**

  Run `just lint-commits HEAD^` and confirm every scaffold commit uses a
  configured type and scope.

- [ ] **Step 3: Run the final verification**

  Run `just verify`. Expected: formatting, Prim lint, structural checks, the
  no-language-toolchain guard, and commit lint all pass.

- [ ] **Step 4: Confirm the final tree**

  Run:

  ```bash
  git status --short --branch
  git ls-files
  ```

  Expected: the worktree is clean; the tracked tree contains policy files, Just
  and check scripts, the seven READMEs, and the design/plan records, but no
  Kotlin, Rust, Cargo, Gradle, Maven, or generated source.

- [ ] **Step 5: Leave hook contents untracked**

  Do not commit `.git/hooks` contents. Confirm `git status --short` remains
  clean after hook installation; the hooks are local checkout state.
