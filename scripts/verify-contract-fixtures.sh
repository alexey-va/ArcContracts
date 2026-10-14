#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

verify_fixture() {
  local jar_path="$1"
  local expected_name="$2"
  local expected_main="$3"
  local required_class="$4"
  local descriptor entries dependency_block

  [[ -s "$jar_path" ]] || fail "Missing or empty isolated fixture JAR: $jar_path"
  descriptor="$(mktemp)"
  entries="$(mktemp)"
  trap 'rm -f "$descriptor" "$entries"' RETURN
  jar tf "$jar_path" > "$entries"
  unzip -p "$jar_path" plugin.yml > "$descriptor" || fail "Missing plugin.yml in $jar_path"
  grep -Fxq "name: $expected_name" "$descriptor" || fail "Unexpected fixture identity in $jar_path"
  grep -Fxq "main: $expected_main" "$descriptor" || fail "Unexpected fixture entrypoint in $jar_path"
  grep -Fxq "$required_class" "$entries" || fail "Missing fixture entrypoint class in $jar_path"

  python3 - "$descriptor" "$expected_name" <<'PY'
import sys
from pathlib import Path

path, name = sys.argv[1:]
lines = Path(path).read_text(encoding="utf-8").splitlines()
values = []
for index, line in enumerate(lines):
    if line.strip().startswith("depend:"):
        inline = line.split(":", 1)[1].strip()
        if inline.startswith("[") and inline.endswith("]"):
            values.extend(part.strip().strip("'\"") for part in inline[1:-1].split(",") if part.strip())
        else:
            for following in lines[index + 1:]:
                stripped = following.strip()
                if stripped.startswith("-"):
                    values.append(stripped[1:].strip().strip("'\""))
                elif stripped and not following.startswith(" "):
                    break
        break
if "ArcContracts" not in values:
    raise SystemExit(f"{name} fixture must depend on ArcContracts; got {values!r}")
PY
}

verify_fixture \
  build/libs/ArcContractsContractTestFixture.jar \
  ARCContractTestFixture \
  ru.arc.contractfixture.ContractTestFixturePlugin \
  ru/arc/contractfixture/ContractTestFixturePlugin.class
verify_fixture \
  build/libs/ArcContractsContractCrashFixture.jar \
  ARCContractCrashFixture \
  ru.arc.contractfixture.ContractCrashFixturePlugin \
  ru/arc/contractfixture/ContractCrashFixturePlugin.class

printf 'Verified isolated ArcContracts test fixtures.\n'
