#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ $# -ge 2 ]] || fail "Usage: publish-staged-maven.sh STAGING_ROOT RELATIVE_ARTIFACT_PATH..."
staging_root="$1"
shift
repo_url="https://repo.rus-crafting.ru/grocermc"
auth_url="https://repo.rus-crafting.ru/api/auth/me"
work_dir="$(mktemp -d)"
password=""
trap 'unset password; rm -rf "$work_dir"' EXIT
chmod 700 "$work_dir"

for relative in "$@"; do
  case "$relative" in
    /*|*../*|../*|*\\*) fail "Artifact paths must be relative Maven repository paths" ;;
    *.jar|*.pom|*.module) ;;
    *) fail "Unsupported staged artifact type: $relative" ;;
  esac
  [[ "$relative" != *$'\n'* && "$relative" != *$'\r'* && "$relative" != *' '* ]] ||
    fail "Artifact paths cannot contain whitespace"
  [[ -s "$staging_root/$relative" ]] || fail "Staged artifact is missing or empty: $relative"
done

# Compare every coordinate anonymously before the first upload. Existing files
# are accepted only when their bytes already match the staged release.
missing_files=()
for relative in "$@"; do
  remote_file="$work_dir/remote"
  if ! status="$(curl --location --silent --show-error --output "$remote_file" --write-out '%{http_code}' --max-time 30 "${repo_url%/}/$relative")"; then
    fail "Could not inspect public artifact: $relative"
  fi
  case "$status" in
    200)
      local_sha="$(sha256sum "$staging_root/$relative" | awk '{print $1}')"
      remote_sha="$(sha256sum "$remote_file" | awk '{print $1}')"
      [[ "$local_sha" == "$remote_sha" ]] || fail "Immutable coordinate conflict: $relative"
      ;;
    404)
      missing_files+=("$relative")
      ;;
    *)
      fail "Could not inspect public artifact $relative (HTTP $status)"
      ;;
  esac
done

if [[ ${#missing_files[@]} -gt 0 ]]; then
  username="${REPOSILITE_PUBLISH_USERNAME:-arc-publisher}"
  password="${REPOSILITE_PUBLISH_PASSWORD:-}"
  [[ -n "$password" ]] || fail "REPOSILITE_PUBLISH_PASSWORD is required to publish missing artifacts"
  case "$username$password" in
    *$'\n'*|*$'\r'*|*' '*) fail "Publisher credentials cannot contain spaces or newlines" ;;
  esac

  netrc_file="$work_dir/netrc"
  printf 'machine repo.rus-crafting.ru\nlogin %s\npassword %s\n' "$username" "$password" > "$netrc_file"
  chmod 600 "$netrc_file"
  unset password

  curl --fail --silent --show-error --max-time 30 --netrc-file "$netrc_file" "$auth_url" -o /dev/null ||
    fail "Reposilite publisher credential was rejected"

  for relative in "${missing_files[@]}"; do
    curl --fail --silent --show-error --max-time 120 --netrc-file "$netrc_file" \
      --upload-file "$staging_root/$relative" "${repo_url%/}/$relative"
  done
fi

# Verify anonymous reads of all versioned artifacts, including existing matches.
for relative in "$@"; do
  remote_file="$work_dir/verified"
  curl --fail --silent --show-error --location --max-time 120 \
    "${repo_url%/}/$relative" -o "$remote_file"
  local_sha="$(sha256sum "$staging_root/$relative" | awk '{print $1}')"
  remote_sha="$(sha256sum "$remote_file" | awk '{print $1}')"
  [[ "$local_sha" == "$remote_sha" ]] || fail "Anonymous readback checksum mismatch: $relative"
done

printf 'Published and verified %s immutable Maven files.\n' "$#"
