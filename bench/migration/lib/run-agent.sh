#!/bin/bash
# Runs the migration agent inside the bench container (installed as /usr/local/bin/run-agent).
# The prompt arrives in $PROMPT and goes to claude on stdin, so neither this process, PID 1 nor
# claude has the prompt on its command line: an agent that kills processes by pattern while
# restarting its app (`pkill -f java`, `kill $(pgrep -f jar)`) must not match its own run.
# The Tessera plugin (/opt/tessera-plugin, baked into the image) is loaded for this session only;
# the prompt doesn't name it, so its skill triggers from its description or not at all.
# CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0: `claude -p` otherwise stops the agent's background tasks
# 10 minutes after its last turn; 0 waits for them, and the wall-clock `timeout` still bounds the run.
printf '%s' "$PROMPT" | CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0 \
  timeout --kill-after=30s "${AGENT_MINUTES}m" claude -p \
  --dangerously-skip-permissions --model "$AGENT_MODEL" --max-budget-usd "$AGENT_BUDGET_USD" \
  --plugin-dir /opt/tessera-plugin \
  --output-format stream-json --verbose --disallowedTools WebFetch WebSearch Workflow
