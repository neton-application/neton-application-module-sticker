#!/bin/bash
# Acceptance-only menu fixture. Does not create accounts/roles or run as server startup.
set -euo pipefail
module=$(cd "$(dirname "$0")/../.." && pwd)
root=${1:?Pass newly created /tmp/sticker-packs.ROOT}
[[ "$root" == /tmp/sticker-packs.* && "$root" != *..* ]]
source "$root/environment.sh"
[[ "$STICKER_PACK_DATABASE_URI" == postgresql://sticker_backend_test:testonly@127.0.0.1:55476/sticker_packs_* ]]
psql=/opt/homebrew/opt/postgresql@16/bin/psql
"$psql" "$STICKER_PACK_DATABASE_URI" -X -v ON_ERROR_STOP=1 >> "$root/migrations.log" <<'SQL'
CREATE SCHEMA fixture_admin;
CREATE TABLE fixture_admin.system_menus (
 id BIGSERIAL PRIMARY KEY, name TEXT NOT NULL, permission TEXT, type INT NOT NULL,
 parent_id BIGINT NOT NULL, path TEXT, component TEXT, icon TEXT, sort INT NOT NULL,
 status INT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
SQL
"$psql" "$STICKER_PACK_DATABASE_URI" -X -v ON_ERROR_STOP=1 -c 'SET search_path TO fixture_admin,public' -f "$module/sql/postgresql/V002__menus.sql" >> "$root/migrations.log"
