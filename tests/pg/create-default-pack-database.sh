#!/bin/bash
# Creates only a fresh isolated database. Never drops/resets/migrates an existing database.
set -euo pipefail
module=$(cd "$(dirname "$0")/../.." && pwd)
root=$(mktemp -d /tmp/sticker-packs.XXXXXX)
database="sticker_packs_$(date +%Y%m%d%H%M%S)_$(basename "$root" | tr '[:upper:].-' '[:lower:]__')"
psql=/opt/homebrew/opt/postgresql@16/bin/psql
uri="postgresql://sticker_backend_test:testonly@127.0.0.1:55476/$database"
"$psql" 'postgresql://sticker_backend_test:testonly@127.0.0.1:55476/postgres' -X -v ON_ERROR_STOP=1 -c "CREATE DATABASE $database" > "$root/migrations.log"
for owner in system infra account sticker; do
  for sql in V001__schema.sql V002__data.sql; do
    "$psql" "$uri" -X -v ON_ERROR_STOP=1 --single-transaction -f "$(dirname "$module")/neton-application-module-$owner/sql/postgresql/$sql" >> "$root/migrations.log"
  done
done
cat > "$root/environment.sh" <<EOF
export STICKER_PACK_DATABASE_URI='$uri'
export STICKER_PACK_ROOT='$root'
export STICKER_PACK_PNG='$(dirname "$module")/privchat-app/privchat/src/commonMain/assets/emoji/apple/images/1f600.png'
EOF
chmod 600 "$root/environment.sh"
printf '%s\n' "$root"
