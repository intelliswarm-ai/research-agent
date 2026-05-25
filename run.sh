#!/usr/bin/env bash
# Run the research agent end-to-end.
#
#   ./run.sh                       # uses bundled sample data/sample.csv
#   ./run.sh path/to/your.csv      # use your own dataset
#
# Required env:
#   OPENAI_API_KEY
#
# Optional env:
#   RESEARCH_AGENT_CHAT_MODEL   default: gpt-4.1-mini
#   RESEARCH_AGENT_EMBED_MODEL  default: text-embedding-3-small
#   OPENALEX_MAILTO             your email — opts into OpenAlex polite pool
#   NCBI_API_KEY                lifts PubMed rate limit
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# Auto-load .env if present so OPENAI_API_KEY etc. are available without
# the user having to remember to `set -a; source .env; set +a` first.
if [[ -f "$PROJECT_DIR/.env" ]]; then
    while IFS= read -r line; do
        case "$line" in
            ''|'#'*) continue ;;
            *=*)
                key="${line%%=*}"
                value="${line#*=}"
                # Strip optional surrounding quotes
                value="${value%\"}"; value="${value#\"}"
                value="${value%\'}"; value="${value#\'}"
                if [[ -z "${!key:-}" ]]; then export "$key=$value"; fi
                ;;
        esac
    done < "$PROJECT_DIR/.env"
fi

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
    echo "ERROR: OPENAI_API_KEY is not set. Add it to .env or export it manually." >&2
    exit 1
fi

CSV="${1:-data/sample.csv}"

mkdir -p output papers .research-agent-index

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
    echo ">>> Building..."
    mvn -q -DskipTests package
fi

JAR="$PROJECT_DIR/target/research-agent-1.0.0-SNAPSHOT.jar"
echo ">>> Running with CSV: $CSV"
java -jar "$JAR" "$CSV"
