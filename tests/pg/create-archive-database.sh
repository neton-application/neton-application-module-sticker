#!/bin/bash
# Only creates a new isolated DB. No existing service or database is mutated.
set -euo pipefail
module=$(cd "$(dirname "$0")/../.." && pwd)
exec bash "$module/tests/pg/create-default-pack-database.sh"
