#!/bin/sh
# Re-run the whole migration benchmark: image, baselines, runs (1 per app unless --runs), summary.
# Arguments pass through to bench.py, e.g. `./run.sh --runs 3` for a milestone round.
exec python3 "$(dirname "$0")/bench.py" all "$@"
