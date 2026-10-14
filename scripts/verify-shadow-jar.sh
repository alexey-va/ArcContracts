#!/usr/bin/env bash
set -euo pipefail

shopt -s nullglob
candidates=(build/libs/ArcContracts-*.jar)
production_jars=()
for candidate in "${candidates[@]}"; do
  [[ "$candidate" == *-plain.jar ]] || production_jars+=("$candidate")
done

[[ ${#production_jars[@]} -eq 1 ]] || {
  printf 'Expected exactly one production ArcContracts JAR, found %s\n' "${#production_jars[@]}" >&2
  exit 1
}

jar_path="${production_jars[0]}"
[[ -s "$jar_path" ]] || { printf 'Production JAR is empty: %s\n' "$jar_path" >&2; exit 1; }

entries="$(mktemp)"
descriptor="$(mktemp)"
trap 'rm -f "$entries" "$descriptor"' EXIT
jar tf "$jar_path" > "$entries"
unzip -p "$jar_path" plugin.yml > "$descriptor"
grep -Fxq 'name: ArcContracts' "$descriptor"
grep -Fxq 'main: ru.arc.contracts.ArcContractsPlugin' "$descriptor"
grep -Fxq '  - ARC' "$descriptor"
grep -Fxq 'kotlin/collections/CollectionsKt.class' "$entries"

if grep -Eq '^(ru/arc/contracts/api/|ru/arc/paper/api/|ru/arc/paper/sql/(PaperSqlServiceProvider|SharedPaperSqlService)[^/]*\.class$|ru/arc/paper/redis/(PaperRedisServiceProvider|SharedPaperRedisService)[^/]*\.class$|ru/arc/paper/metrics/PaperArcMetricsProvider[^/]*\.class$|ru/arc/metrics/core/ArcMetricsRuntime[^/]*\.class$|ru/arc/contractfixture/|org/bukkit/|io/papermc/)' "$entries"; then
  printf 'Production JAR embeds a host-owned API/provider, test fixture, or server-provided Paper class.\n' >&2
  exit 1
fi

printf 'Verified production JAR: %s\n' "$jar_path"
