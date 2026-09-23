#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd -- "$script_dir/.." && pwd)"

required_readmes=(
  "modules/ridlc-gen-kotlin/README.md"
  "modules/ridl-rt-kt/README.md"
  "modules/ridl-rt-kt-loopback/README.md"
  "modules/ridl-rt-kt-coroutines/README.md"
  "modules/conformance/README.md"
  "samples/cabin/README.md"
  "shared/README.md"
)

required_root_files=(
  ".editorconfig"
  ".gitignore"
  ".git-std.toml"
  "AGENTS.md"
  "CONTRIBUTING.md"
  "LICENSE"
  "README.md"
  "justfile"
)

for relative_path in "${required_root_files[@]}" "${required_readmes[@]}"; do
  if [[ ! -f "$root_dir/$relative_path" ]]; then
    printf 'missing required path: %s\n' "$relative_path" >&2
    exit 1
  fi
done

while IFS= read -r -d '' tracked_path; do
  case "$tracked_path" in
    *.kt|*.kts|*.java|*.rs|\
    Cargo.toml|*/Cargo.toml|\
    build.gradle*|*/build.gradle*|settings.gradle*|*/settings.gradle*|\
    pom.xml|*/pom.xml|\
    gradlew|*/gradlew|gradlew.bat|*/gradlew.bat|\
    mvnw|*/mvnw|mvnw.cmd|*/mvnw.cmd|\
    .gradle/*|*/.gradle/*|\
    build/*|*/build/*|out/*|*/out/*|target/*|*/target/*|\
    generated/*|*/generated/*|generated-sources/*|*/generated-sources/*|\
    gen/*|*/gen/*|dist/*|*/dist/*|\
    *.class|*.jar|*.aar|*.war|*.ear|*.kotlin_module|*.pom|*.module)
      printf 'forbidden tracked file: %s\n' "$tracked_path" >&2
      exit 1
      ;;
  esac
done < <(git -C "$root_dir" ls-files -z)

for relative_path in "${required_readmes[@]}"; do
  readme="$root_dir/$relative_path"
  if ! grep -Eq '^##[[:space:]]+Responsibility:?[[:space:]]*$' "$readme"; then
    printf 'README is missing a Responsibility heading: %s\n' "$relative_path" >&2
    exit 1
  fi
  if ! grep -Eq '^##[[:space:]]+Status:?[[:space:]]*$' "$readme"; then
    printf 'README is missing a Status heading: %s\n' "$relative_path" >&2
    exit 1
  fi
  printf 'checked README: %s\n' "$relative_path"
done

printf 'scaffold check passed\n'
