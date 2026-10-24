#!/usr/bin/env bash
# Backwards-compatible entry point: install the published APKs through the safe setup path.
set -euo pipefail
exec bash "$(dirname "$0")/setup.sh" "$@"
