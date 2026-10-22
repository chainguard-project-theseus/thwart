#!/bin/bash
# Runs the migration agent inside the bench container (installed as /usr/local/bin/run-agent).
# The prompt arrives in $PROMPT and goes to claude on stdin, so neither this process, PID 1 nor
# claude has the prompt on its command line: an agent that kills processes by pattern while
# restarting its app (`pkill -f java`, `kill $(pgrep -f jar)`) must not match its own run.
printf '%s' "$PROMPT" | timeout --kill-after=30s "${AGENT_MINUTES}m" claude -p \
  --dangerously-skip-permissions --model "$AGENT_MODEL" --max-budget-usd "$AGENT_BUDGET_USD" \
  --output-format stream-json --verbose --disallowedTools WebFetch WebSearch Workflow
