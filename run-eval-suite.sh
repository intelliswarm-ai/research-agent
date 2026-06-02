#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# run-eval-suite.sh — Multi-hypothesis evaluation suite for research-agent
#
# Runs 5 test hypotheses covering different quality dimensions:
#   1. Well-supported domain (cognitive training + Alzheimer) — should score high
#   2. Contested domain (ultra-processed food + obesity) — must find contradicting evidence
#   3. Rare/narrow domain (visceral fat + insulin resistance) — tests retrieval breadth
#   4. Planning-paralysis stress test (junk food hypothesis) — anti-paralysis guard
#   5. Strong domain with known landmarks (SGLT2 + heart failure) — golden reference check
#
# Usage:
#   ./run-eval-suite.sh                     # run all 5 hypotheses
#   ./run-eval-suite.sh --hypothesis 1      # run hypothesis 1 only
#   ./run-eval-suite.sh --skip-build        # skip mvn build
#   ./run-eval-suite.sh --report-only       # aggregate existing results, no new runs
#
# Output:
#   output/eval_suite_<timestamp>.tsv       — tab-separated score sheet
#   output/eval_suite_<timestamp>_report.md — markdown summary with top defects
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"
JAR="$SCRIPT_DIR/target/research-agent-*.jar"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
SUITE_TSV="$OUTPUT_DIR/eval_suite_${TIMESTAMP}.tsv"
SUITE_REPORT="$OUTPUT_DIR/eval_suite_${TIMESTAMP}_report.md"

# ── Test hypotheses ──────────────────────────────────────────────────────────
HYPOTHESES=(
  "Does cognitive training reduce the risk of Alzheimer's disease in older adults through neuroplasticity mechanisms?"
  "Does consuming ultra-processed food increase the risk of obesity and type 2 diabetes in adults?"
  "Is there a causal link between visceral fat accumulation and insulin resistance in humans?"
  "Do SGLT2 inhibitors reduce hospitalisation for heart failure in patients with HFrEF?"
  "Does regular aerobic exercise prevent cognitive decline and dementia in adults aged 60 and older?"
)

HYPOTHESIS_LABELS=(
  "cognitive-training-alzheimer"
  "ultra-processed-food-t2dm"
  "visceral-fat-insulin-resistance"
  "sglt2-heart-failure"
  "aerobic-exercise-dementia"
)

# ── Argument parsing ─────────────────────────────────────────────────────────
SKIP_BUILD=false
REPORT_ONLY=false
SINGLE_IDX=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build)   SKIP_BUILD=true; shift ;;
    --report-only)  REPORT_ONLY=true; shift ;;
    --hypothesis)   SINGLE_IDX="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

mkdir -p "$OUTPUT_DIR"

# ── Build ────────────────────────────────────────────────────────────────────
if [ "$REPORT_ONLY" = false ] && [ "$SKIP_BUILD" = false ]; then
  echo "════════════════════════════════════════════════════════"
  echo "  Building research-agent..."
  echo "════════════════════════════════════════════════════════"
  cd "$SCRIPT_DIR"
  mvn clean package -DskipTests -q
fi

# Find the jar
JAR_PATH=$(ls "$SCRIPT_DIR"/target/research-agent-*.jar 2>/dev/null | head -1)
if [ -z "$JAR_PATH" ]; then
  echo "ERROR: No research-agent jar found in target/. Run with --skip-build after a successful build."
  exit 1
fi

echo ""
echo "════════════════════════════════════════════════════════"
echo "  RESEARCH-AGENT EVAL SUITE"
echo "  JAR: $(basename "$JAR_PATH")"
echo "  Timestamp: $TIMESTAMP"
echo "════════════════════════════════════════════════════════"
echo ""

# ── TSV header ───────────────────────────────────────────────────────────────
if [ "$REPORT_ONLY" = false ]; then
  echo -e "hypothesis_label\toverall\tstructure\tevidence\tcitations\tbalance\tverdict\tefficiency\tpapers\tcost_usd\twall_sec\ttop_defect" > "$SUITE_TSV"
fi

# ── Run each hypothesis ───────────────────────────────────────────────────────
run_one() {
  local idx=$1
  local hypothesis="${HYPOTHESES[$idx]}"
  local label="${HYPOTHESIS_LABELS[$idx]}"

  echo "────────────────────────────────────────────────────────"
  echo "  [$((idx+1))/${#HYPOTHESES[@]}] $label"
  echo "  Hypothesis: $hypothesis"
  echo "────────────────────────────────────────────────────────"

  local before_ms
  before_ms=$(date +%s%3N)

  # Run agent
  java -Xmx4g -jar "$JAR_PATH" --batch "$hypothesis" 2>&1 || true

  local after_ms
  after_ms=$(date +%s%3N)
  local wall_sec=$(( (after_ms - before_ms) / 1000 ))

  # Find the latest eval_result JSON
  local eval_json
  eval_json=$(ls -t "$OUTPUT_DIR"/eval_result_*.json 2>/dev/null | head -1 || echo "")

  if [ -z "$eval_json" ]; then
    echo "  WARNING: no eval_result JSON found for $label"
    echo -e "$label\t0.0\t0\t0\t0\t0\t0\t0\t0\t0.00\t$wall_sec\tno-eval-result" >> "$SUITE_TSV"
    return
  fi

  # Parse scores with python3
  python3 - <<PYEOF >> "$SUITE_TSV"
import json, sys
with open('$eval_json') as f:
    d = json.load(f)
overall    = d.get('scoreOverall', 0)
structure  = d.get('scoreStructure', 0)
evidence   = d.get('scoreEvidence', 0)
citations  = d.get('scoreCitations', 0)
balance    = d.get('scoreBalance', 0)
verdict    = d.get('scoreVerdict', 0)
efficiency = d.get('scoreEfficiency', 0)
papers     = d.get('papersIngested', 0)
cost       = d.get('costUsd', 0)
issues     = d.get('qualityIssues', [])
# Find the first DEFECT[] issue
top_defect = next((i.split(':')[0] for i in issues if i.startswith('DEFECT[')), 'none')
print(f"$label\t{overall:.2f}\t{structure:.1f}\t{evidence:.1f}\t{citations:.1f}\t{balance:.1f}\t{verdict:.1f}\t{efficiency:.1f}\t{papers}\t{cost:.4f}\t$wall_sec\t{top_defect}")
PYEOF

  echo "  Done: $(python3 -c "import json; d=json.load(open('$eval_json')); print(f\"score={d.get('scoreOverall',0):.1f}/10  papers={d.get('papersIngested',0)}  cost=\${d.get('costUsd',0):.4f}\")" 2>/dev/null || echo 'parse error')"
  echo ""
}

if [ "$REPORT_ONLY" = false ]; then
  if [ -n "$SINGLE_IDX" ]; then
    run_one "$((SINGLE_IDX - 1))"
  else
    for i in "${!HYPOTHESES[@]}"; do
      run_one "$i"
    done
  fi
fi

# ── Generate markdown report ──────────────────────────────────────────────────
echo "════════════════════════════════════════════════════════"
echo "  Generating aggregate report..."
echo "════════════════════════════════════════════════════════"

# Use the latest TSV if report-only
if [ "$REPORT_ONLY" = true ]; then
  SUITE_TSV=$(ls -t "$OUTPUT_DIR"/eval_suite_*.tsv 2>/dev/null | head -1 || echo "")
  if [ -z "$SUITE_TSV" ]; then
    echo "No eval suite TSV found in $OUTPUT_DIR"
    exit 1
  fi
fi

python3 - <<PYEOF
import csv, sys, os
from pathlib import Path

tsv_path = '$SUITE_TSV'
report_path = '$SUITE_REPORT'

rows = []
with open(tsv_path) as f:
    reader = csv.DictReader(f, delimiter='\t')
    rows = list(reader)

if not rows:
    print("No data rows in TSV.")
    sys.exit(0)

def flt(v, default=0.0):
    try: return float(v)
    except: return default

lines = ["# Research-Agent Eval Suite Report", f"", f"**Run:** {os.path.basename(tsv_path).replace('.tsv','')}", ""]

# Summary table
lines.append("## Scores Summary")
lines.append("")
lines.append("| Hypothesis | Overall | Structure | Evidence | Citations | Balance | Verdict | Efficiency | Papers | Cost |")
lines.append("|---|---|---|---|---|---|---|---|---|---|")
for r in rows:
    overall = flt(r.get('overall', 0))
    bar = "🟢" if overall >= 7 else "🟡" if overall >= 4 else "🔴"
    lines.append(f"| {r['hypothesis_label']} | {bar} **{overall:.1f}** | {flt(r.get('structure',0)):.1f} | {flt(r.get('evidence',0)):.1f} | {flt(r.get('citations',0)):.1f} | {flt(r.get('balance',0)):.1f} | {flt(r.get('verdict',0)):.1f} | {flt(r.get('efficiency',0)):.1f} | {r.get('papers','?')} | \${flt(r.get('cost_usd',0)):.4f} |")

lines.append("")

# Aggregate stats
if rows:
    avg_overall   = sum(flt(r.get('overall',0)) for r in rows) / len(rows)
    avg_evidence  = sum(flt(r.get('evidence',0)) for r in rows) / len(rows)
    avg_balance   = sum(flt(r.get('balance',0)) for r in rows) / len(rows)
    total_papers  = sum(int(r.get('papers', 0) or 0) for r in rows)
    total_cost    = sum(flt(r.get('cost_usd', 0)) for r in rows)
    lines.append("## Aggregate Stats")
    lines.append("")
    lines.append(f"- **Average overall score:** {avg_overall:.2f} / 10")
    lines.append(f"- **Average evidence score:** {avg_evidence:.2f} / 10")
    lines.append(f"- **Average balance score:** {avg_balance:.2f} / 10")
    lines.append(f"- **Total papers ingested:** {total_papers}")
    lines.append(f"- **Total cost:** \${total_cost:.4f}")
    lines.append("")

# Top defects across all runs
defects = [r.get('top_defect','none') for r in rows if r.get('top_defect','none') != 'none']
if defects:
    from collections import Counter
    top = Counter(defects).most_common()
    lines.append("## Top Defects (most common across runs)")
    lines.append("")
    for defect, count in top:
        lines.append(f"- `{defect}` — {count} run(s)")
    lines.append("")

# Weak dimensions (avg < 5)
weak_dims = []
dim_names = ['structure','evidence','citations','balance','verdict','efficiency']
for dim in dim_names:
    avg_dim = sum(flt(r.get(dim, 0)) for r in rows) / len(rows) if rows else 0
    if avg_dim < 5:
        weak_dims.append((dim, avg_dim))
if weak_dims:
    lines.append("## Weakest Dimensions (avg < 5.0)")
    lines.append("")
    for dim, avg_val in sorted(weak_dims, key=lambda x: x[1]):
        lines.append(f"- **{dim}**: {avg_val:.2f} / 10 — needs improvement")
    lines.append("")

report_text = "\n".join(lines)
print(report_text)
with open(report_path, 'w') as f:
    f.write(report_text)
print(f"\nReport saved to: {report_path}")
PYEOF

echo ""
echo "════════════════════════════════════════════════════════"
echo "  Eval suite complete."
echo "  Scores: $SUITE_TSV"
echo "  Report: $SUITE_REPORT"
echo "════════════════════════════════════════════════════════"
