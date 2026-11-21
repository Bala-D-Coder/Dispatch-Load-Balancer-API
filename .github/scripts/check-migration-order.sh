#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# check-migration-order.sh  <base-sha>  <head-sha>
#
# Validates Flyway migration files changed between BASE and HEAD do not break
# an already-migrated production database. Blocks the deploy if any of these:
#
#   1. A migration file already shipped to production was EDITED
#      (Flyway's checksum no longer matches -> validate fails, app won't boot)
#   2. A migration file already shipped to production was RENAMED / DELETED
#   3. A new migration version is LOWER THAN OR EQUAL TO the highest shipped
#      version (out-of-order or duplicate version — Flyway rejects both by
#      default)
#
# Migrations in this repo: src/main/resources/db/migration/V<n>__<name>.sql
# The project rule is one migration per change set, so case 3 is the realistic
# failure: two branches both adding V2.
#
# Version parsing follows Flyway's grammar, where V2_1__x.sql and V2.1__x.sql
# are both version 2.1. Comparison is `sort -V`, so V10 > V2 and 1.10 > 1.9 —
# a plain string sort gets both of those wrong.
# ---------------------------------------------------------------------------
set -euo pipefail

BASE="${1:?Usage: $0 <base-sha> <head-sha>}"
HEAD="${2:?Usage: $0 <base-sha> <head-sha>}"

MIGRATION_DIR="src/main/resources/db/migration"
# V<version>__<description>.sql, version = digits joined by . or _
MIGRATION_RE='/V[0-9][0-9._]*__[^/]*\.sql$'

echo "=== Migration Guard ==="
echo "Base: $BASE"
echo "Head: $HEAD"
echo ""

version_of() { # .../V2_1__late.sql -> 2.1
  basename "$1" | sed -E 's/^V([0-9][0-9._]*)__.*/\1/' | tr '_' '.'
}

# --- 1/2. Migration files at BASE and at HEAD ------------------------------
mapfile -t BASE_FILES < <(git ls-tree -r --name-only "$BASE" -- "$MIGRATION_DIR" 2>/dev/null \
  | grep -E "$MIGRATION_RE" | sort || true)
mapfile -t HEAD_FILES < <(git ls-tree -r --name-only "$HEAD" -- "$MIGRATION_DIR" 2>/dev/null \
  | grep -E "$MIGRATION_RE" | sort || true)

ERRORS=0

# --- 3. Detect edited or deleted files that were already shipped ------------
for f in "${BASE_FILES[@]:-}"; do
  [ -z "$f" ] && continue
  filename=$(basename "$f")

  # Renamed or deleted: gone from HEAD entirely.
  if ! printf '%s\n' "${HEAD_FILES[@]:-}" | grep -qxF "$f"; then
    echo "::error::DELETED or RENAMED shipped migration: $filename"
    ERRORS=$((ERRORS + 1))
    continue
  fi

  # Edited: same path, different bytes.
  BASE_HASH=$(git show "$BASE:$f" | sha256sum | cut -d' ' -f1)
  HEAD_HASH=$(git show "$HEAD:$f" | sha256sum | cut -d' ' -f1)
  if [ "$BASE_HASH" != "$HEAD_HASH" ]; then
    echo "::error::EDITED shipped migration: $filename"
    ERRORS=$((ERRORS + 1))
  fi
done

# --- 4. Detect out-of-order or duplicate new migrations ---------------------
LAST_VERSION=""
for f in "${BASE_FILES[@]:-}"; do
  [ -z "$f" ] && continue
  v=$(version_of "$f")
  if [ -z "$LAST_VERSION" ] || [ "$(printf '%s\n%s\n' "$v" "$LAST_VERSION" | sort -V | tail -1)" = "$v" ]; then
    LAST_VERSION="$v"
  fi
done

if [ -n "$LAST_VERSION" ]; then
  for f in "${HEAD_FILES[@]:-}"; do
    [ -z "$f" ] && continue
    # Only files that are NEW (not present at BASE)
    if printf '%s\n' "${BASE_FILES[@]:-}" | grep -qxF "$f"; then
      continue
    fi
    new=$(version_of "$f")
    # new is the smaller-or-equal side of (new, LAST) -> it sorts before or equals LAST
    if [ "$new" = "$LAST_VERSION" ] \
       || [ "$(printf '%s\n%s\n' "$new" "$LAST_VERSION" | sort -V | head -1)" = "$new" ]; then
      echo "::error::Out-of-order or duplicate migration: $(basename "$f") (version $new, highest shipped is $LAST_VERSION)"
      ERRORS=$((ERRORS + 1))
    fi
  done
fi

# --- Result ----------------------------------------------------------------
echo ""
if [ "$ERRORS" -gt 0 ]; then
  echo "::error::Migration guard failed with $ERRORS violation(s). Fix before deploying to production."
  exit 1
fi

echo "Migration guard passed — all Flyway migrations look safe to deploy."
