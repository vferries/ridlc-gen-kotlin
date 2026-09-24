#!/usr/bin/env bash
# Validates every generated `.aidl` file with the Android SDK's `aidl` tool
# (docs/design.md §7, "The AIDL is valid"). The files are written by the
# conformance build under modules/conformance/build/aidl; stage K3b generates
# them, and until then there is nothing to check.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd -- "$script_dir/.." && pwd)"
aidl_root="$root_dir/modules/conformance/build/aidl"

aidl_tool="$(command -v aidl || true)"
if [[ -z "$aidl_tool" && -n "${ANDROID_HOME:-}" ]]; then
  aidl_tool="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name aidl -type f 2>/dev/null | sort -V | tail -1)"
fi
if [[ -z "$aidl_tool" ]]; then
  printf 'aidl not found: put it on PATH or set ANDROID_HOME\n' >&2
  exit 1
fi

if [[ ! -d "$aidl_root" ]]; then
  printf 'no generated AIDL to check before stage K3b\n'
  exit 0
fi

count=0
while IFS= read -r -d '' file; do
  "$aidl_tool" --lang=java -I "$aidl_root" -o "$(mktemp -d)" "$file"
  count=$((count + 1))
done < <(find "$aidl_root" -name '*.aidl' -print0)
printf 'checked %d AIDL files\n' "$count"
