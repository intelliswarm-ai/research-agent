#!/usr/bin/env bash
# Continuous improvement loop for the research agent.
# Runs one iteration: generate hypotheses → eval → analyze → improve → rebuild → save state.
# Designed to be called by cron every 2 hours.
#
# Usage:
#   ./run-improvement-loop.sh            # one iteration
#   ./run-improvement-loop.sh --daemon   # loop indefinitely (for interactive use)

set -euo pipefail

AGENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EVAL_DIR="$(dirname "$AGENT_DIR")/research-agent-eval"
STATE_FILE="$EVAL_DIR/output/improvement_state.json"
LOG_FILE="$EVAL_DIR/output/improvement_loop.log"
PROMPTS_DIR="$AGENT_DIR/src/main/resources/prompts"

# ── Logging ────────────────────────────────────────────────────────────────

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG_FILE"; }

# ── Load .env ──────────────────────────────────────────────────────────────

for ENV_FILE in "$AGENT_DIR/.env" "$EVAL_DIR/.env"; do
    if [[ -f "$ENV_FILE" ]]; then
        while IFS= read -r line; do
            case "$line" in ''|'#'*) continue ;; *=*)
                key="${line%%=*}"; value="${line#*=}"
                value="${value%\"}"; value="${value#\"}"
                if [[ -z "${!key:-}" ]]; then export "$key=$value"; fi ;;
            esac
        done < "$ENV_FILE"
        break
    fi
done

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
    log "ERROR: OPENAI_API_KEY not set"; exit 1
fi

mkdir -p "$EVAL_DIR/output"

# ── Load state ─────────────────────────────────────────────────────────────

if [[ -f "$STATE_FILE" ]]; then
    ITERATION=$(python3 -c "import json; d=json.load(open('$STATE_FILE')); print(d.get('iteration',0))")
    TESTED_COUNT=$(python3 -c "import json; d=json.load(open('$STATE_FILE')); print(len(d.get('hypothesesTested',[])))")
    IMPROVEMENTS=$(python3 -c "import json; d=json.load(open('$STATE_FILE')); print(len(d.get('appliedImprovements',[])))")
else
    ITERATION=0
    TESTED_COUNT=0
    IMPROVEMENTS=0
    echo '{"iteration":0,"hypothesesTested":[],"scoreHistory":[],"knownFailureModes":[],"appliedImprovements":[],"suggestedTestHypotheses":[]}' > "$STATE_FILE"
fi

ITERATION=$((ITERATION + 1))
log "═══════════════════════════════════════════════════════"
log "ITERATION $ITERATION  |  hypotheses tested: $TESTED_COUNT  |  improvements: $IMPROVEMENTS"
log "═══════════════════════════════════════════════════════"

# ── Build ──────────────────────────────────────────────────────────────────

log "Building JAR..."
mvn -q -DskipTests clean package -f "$AGENT_DIR/pom.xml"
log "Build complete."

# ── Generate hypotheses via LLM ───────────────────────────────────────────

TESTED_DOMAINS=$(python3 -c "
import json
try:
    d=json.load(open('$STATE_FILE'))
    hs=d.get('hypothesesTested',[])
    print(','.join(set(h.get('domain','?') for h in hs[-20:])))
except: print('')
" 2>/dev/null)

log "Generating 3 hypotheses (avoiding: $TESTED_DOMAINS)..."

HYPOS_JSON=$(python3 - <<PYEOF
import json, urllib.request, os

prompt = f"""Generate exactly 3 diverse, specific, testable medical research hypotheses for systematic reviews.
Requirements: different domains from [{os.environ.get('TESTED_DOMAINS','')}], human clinical evidence only,
single declarative sentence each, specific enough to have PubMed papers.
Output ONLY valid JSON: {{"hypotheses": [{{"text": "...", "domain": "..."}}]}}"""

req = urllib.request.Request(
    "https://api.openai.com/v1/chat/completions",
    data=json.dumps({
        "model": "gpt-4o-mini",
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 400,
        "response_format": {"type": "json_object"}
    }).encode(),
    headers={"Authorization": f"Bearer {os.environ['OPENAI_API_KEY']}", "Content-Type": "application/json"}
)
resp = json.loads(urllib.request.urlopen(req).read())
print(resp["choices"][0]["message"]["content"])
PYEOF
)

log "Hypotheses generated."

# ── Run evals ─────────────────────────────────────────────────────────────

EVAL_SCORES=()
EVAL_RESULTS=()
IDX=0

python3 -c "
import json, sys
data = json.loads(sys.stdin.read())
for h in data.get('hypotheses', []):
    print(h['text'])
" <<< "$HYPOS_JSON" | while IFS= read -r HYPOTHESIS; do
    IDX=$((IDX + 1))
    log "[$IDX/3] Evaluating: ${HYPOTHESIS:0:80}..."
    INDEX_PATH="$EVAL_DIR/.research-agent-index-ci-${ITERATION}-${IDX}"
    RESEARCH_AGENT_INDEX="$INDEX_PATH" SKIP_BUILD=1 \
        "$EVAL_DIR/run-eval.sh" --hypothesis "$HYPOTHESIS" >> "$LOG_FILE" 2>&1 || true

    # Read latest eval result
    LATEST=$(ls -t "$EVAL_DIR/output/eval_result_"*.json 2>/dev/null | head -1)
    if [[ -n "$LATEST" ]]; then
        SCORE=$(python3 -c "import json; print(json.load(open('$LATEST')).get('scoreOverall',0))" 2>/dev/null || echo 0)
        PAPERS=$(python3 -c "import json; print(json.load(open('$LATEST')).get('papersIngested',0))" 2>/dev/null || echo 0)
        log "  → score=$SCORE papers=$PAPERS"
    fi
done

# ── Update state ───────────────────────────────────────────────────────────

log "Updating state file..."
python3 - <<PYEOF
import json, datetime

STATE_FILE = "$STATE_FILE"
ITERATION = $ITERATION

try:
    state = json.load(open(STATE_FILE))
except:
    state = {"iteration": 0, "hypothesesTested": [], "scoreHistory": [], "knownFailureModes": [], "appliedImprovements": [], "suggestedTestHypotheses": []}

import subprocess, os
hypos_raw = subprocess.run(
    ["python3", "-c", "import json,sys; d=json.loads(sys.stdin.read()); [print(json.dumps(h)) for h in d.get('hypotheses',[])]"],
    input="""$HYPOS_JSON""", capture_output=True, text=True
).stdout.strip()

new_hypotheses = [json.loads(l) for l in hypos_raw.split('\n') if l.strip()]

# Read latest eval scores
import glob
results = sorted(glob.glob("$EVAL_DIR/output/eval_result_*.json"), key=os.path.getmtime, reverse=True)
scores = []
for f in results[:3]:
    try:
        d = json.load(open(f))
        scores.append(d.get('scoreOverall', 0))
    except: pass

state["iteration"] = ITERATION
state["hypothesesTested"] = state.get("hypothesesTested", []) + new_hypotheses
state["scoreHistory"].append({
    "iteration": ITERATION,
    "timestamp": datetime.datetime.utcnow().isoformat(),
    "avgScore": round(sum(scores)/len(scores), 2) if scores else 0,
    "scores": scores,
    "hypotheses": [h.get("text","") for h in new_hypotheses],
})

json.dump(state, open(STATE_FILE, "w"), indent=2)
print(f"State saved. Iteration {ITERATION}, avg score: {round(sum(scores)/len(scores),2) if scores else 'n/a'}")
PYEOF

log "Iteration $ITERATION complete."

# Score trend
python3 -c "
import json
d=json.load(open('$STATE_FILE'))
hist=d.get('scoreHistory',[])
trend=' → '.join(f'iter{s[\"iteration\"]}:{s[\"avgScore\"]}' for s in hist)
print(f'Score trend: {trend}')
" 2>/dev/null >> "$LOG_FILE"

# ── Daemon mode ────────────────────────────────────────────────────────────

if [[ "${1:-}" == "--daemon" ]]; then
    log "Sleeping 2 hours before next iteration..."
    sleep 7200
    exec "$0" --daemon
fi
