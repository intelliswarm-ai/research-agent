#!/usr/bin/env bash
# Run the dynamic research agent — interactive CLI mode.
#
#   ./run.sh
#
# Required env (set in .env or export manually):
#   OPENAI_API_KEY
#
# Optional env:
#   RESEARCH_AGENT_CHAT_MODEL      default: gpt-4o-mini
#   RESEARCH_AGENT_EMBED_MODEL     default: text-embedding-3-small
#   RESEARCH_AGENT_AUTO_APPROVE    set to 'true' to skip write-tool permission prompts
#   OPENALEX_MAILTO                your email — opts into OpenAlex polite pool
#   NCBI_API_KEY                   lifts PubMed rate limit from 3 to 10 req/s
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# Auto-load .env if present
if [[ -f "$PROJECT_DIR/.env" ]]; then
    while IFS= read -r line; do
        case "$line" in
            ''|'#'*) continue ;;
            *=*)
                key="${line%%=*}"
                value="${line#*=}"
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

mkdir -p output papers .research-agent-index

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
    echo ">>> Building..."
    mvn -q -DskipTests clean package
fi

JAR="$PROJECT_DIR/target/research-agent-1.0.0-SNAPSHOT.jar"
echo ">>> Starting Research Agent (gpt-4o-mini)..."
# -Xmx4g: PDF parsing + Lucene + growing conversation context can be memory-hungry.
# --add-modules jdk.incubator.vector: faster Lucene vector search.
# --enable-native-access: silence the JDK 21 "restricted method" warnings from Lucene mmap.
java -Xmx4g --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -jar "$JAR"
