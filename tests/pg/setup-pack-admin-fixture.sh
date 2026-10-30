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
-- The fresh fixture already installed the real system/sticker baseline seeds.
CREATE TABLE fixture_admin.system_menus AS TABLE public.system_menus;
SQL
