set shell := ["bash", "-euo", "pipefail", "-c"]

fmt:
    prim fmt .

fmt-check:
    prim fmt --check .

check:
    prim lint .
    scripts/check-repo.sh
    ./gradlew check

test:
    ./gradlew test

build: fmt-check check
    ./gradlew assemble

# The generated AIDL through the Android SDK's `aidl` tool; CI runs it in the
# job that installs the build-tools (docs/design.md §6, §7).
aidl-check:
    ./gradlew :conformance:test
    scripts/check-aidl.sh

# The plugin distribution: bin/ridlc-gen-kotlin over lib/ridlc-gen-kotlin-all.jar.
dist:
    ./gradlew :ridlc-gen-kotlin:shadowDistZip :ridlc-gen-kotlin:shadowDistTar :ridlc-gen-kotlin:installShadowDist
    @echo "modules/ridlc-gen-kotlin/build/install/ridlc-gen-kotlin/bin/ridlc-gen-kotlin"

lint-commits base="main":
    @if git show-ref --verify --quiet "refs/remotes/origin/{{base}}"; then \
      base_ref="origin/{{base}}"; \
    elif git rev-parse --verify --quiet "{{base}}^{commit}" >/dev/null 2>&1; then \
      base_ref="{{base}}"; \
    else \
      printf 'Unable to resolve commit-lint base: %s or origin/%s\n' "{{base}}" "{{base}}" >&2; \
      exit 1; \
    fi; \
    git std lint --range "$base_ref..HEAD"

verify: lint-commits build

bootstrap:
    @missing=""; \
      for tool in git-std prim just java; do \
        if ! command -v "$tool" >/dev/null 2>&1; then missing="$missing $tool"; fi; \
      done; \
      if [ -n "$missing" ]; then \
        printf 'Missing required tools:%s\n' "$missing"; \
        exit 1; \
      fi; \
      if ! java -version 2>&1 | grep -Eq 'version "(1[7-9]|[2-9][0-9])'; then \
        printf 'JDK 17 or later is required\n'; \
        exit 1; \
      fi; \
      printf 'Required tools available: git-std prim just java\n'
