#!/bin/sh
# Re-run the whole migration benchmark: image, baselines, 3 runs per app, summary.
# Arguments pass through to bench.py, e.g. `./run.sh --runs 1 spring-petclinic`.
exec python3 "$(dirname "$0")/bench.py" all "$@"
