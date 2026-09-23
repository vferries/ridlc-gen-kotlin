set shell := ["bash", "-euo", "pipefail", "-c"]

fmt:
    prim fmt .

fmt-check:
    prim fmt --check .

check:
    prim lint .
    scripts/check-scaffold.sh

test:
    scripts/check-scaffold.sh
    @echo "No implementation tests are configured yet."

build: fmt-check check

lint-commits base="main":
    git std lint --range "origin/{{base}}..HEAD"

verify: lint-commits build

bootstrap:
    @missing=""; \
      for tool in git-std prim just; do \
        if ! command -v "$tool" >/dev/null 2>&1; then missing="$missing $tool"; fi; \
      done; \
      if [ -n "$missing" ]; then \
        printf 'Missing required tools:%s\n' "$missing"; \
        exit 1; \
      fi; \
      printf 'Required tools available: git-std prim just\n'
