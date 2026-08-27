# AI Document Fact Checker

Upload a PDF, ask grounded questions about it with visual evidence highlighting, and fact-check its
claims against live web research. Runs entirely on your own machine - no API keys, no per-request
cost, no third-party LLM/search service in the loop.

## Architecture

- **frontend/** - Angular 18 (standalone components) SPA. PDF rendering + highlight overlay via
  pdf.js, Tailwind for styling.
- **backend/** - Spring Boot 3.3 (Java 21) REST API. JWT auth, H2 (file-based, zero external DB
  service), Apache PDFBox for text+position extraction, Tess4J/Tesseract for OCR fallback on
  scanned pages.
- **AI, fully local via [Ollama](https://ollama.com)**: `nomic-embed-text` for embeddings,
  `llama3.2` for grounded generation, claim extraction, and source-stance classification.
- **Web search, fully local via [SearXNG](https://docs.searxng.org/)**: a self-hosted, open-source
  metasearch engine (aggregates DuckDuckGo, Google, etc.) - no API key, no per-query cost.

See code comments in `VerdictCalculator`, `RagService`, and `Prompts` for how retrieval confidence,
document fidelity, and web verification confidence are computed deterministically rather than
self-reported by the LLM.

**Trade-off to know:** a local 3B model is noticeably weaker than a hosted frontier model at
structured output and nuanced reasoning. JSON-mode decoding (Ollama's `format: "json"`) keeps the
structured fields (verdict, stance, confidence) reliable; free-text fields like the fact-check
summary can occasionally be a little less polished. If you'd rather trade "fully local" for
quality, swapping `OllamaChatClient` for a hosted API is a small, isolated change (see
`llm/OllamaChatClient.java`).

## Prerequisites

1. **Java 21** and **Maven**.
2. **Node.js 18+** and npm.
3. **[Docker](https://www.docker.com/)** - runs Ollama and SearXNG as containers, so nothing needs
   installing directly on your machine besides Docker itself.
4. **Tesseract OCR**, only needed for scanned/image-based PDFs (PDFs with a real text layer work
   without it):
   - Windows: install via the [UB-Mannheim Tesseract installer](https://github.com/UB-Mannheim/tesseract/wiki)
     and note the `tessdata` folder path (e.g. `C:\Program Files\Tesseract-OCR\tessdata`).
   - If you skip this, uploads still work; pages that turn out to be scanned images will simply
     have no extracted text (visible in the app rather than silently wrong).

## One-time setup: Ollama + SearXNG

```bash
# Embeddings + local LLM
docker run -d --name factchecker-ollama -p 11434:11434 -v ollama_data:/root/.ollama ollama/ollama
docker exec factchecker-ollama ollama pull nomic-embed-text
docker exec factchecker-ollama ollama pull llama3.2

# Self-hosted web search (settings.yml enables its JSON API, which is off by default)
docker run -d --name factchecker-searxng -p 8888:8080 \
  -v "$(pwd)/searxng/settings.yml:/etc/searxng/settings.yml" \
  searxng/searxng
```

After the first setup, just `docker start factchecker-ollama factchecker-searxng` to bring them
back up - the pulled models and config persist in the named volume / mounted file.

## Backend setup

```bash
export JWT_SECRET=some-long-random-string
export TESSDATA_PATH="C:/Program Files/Tesseract-OCR/tessdata"   # optional, for OCR

cd backend
mvn spring-boot:run
```

The API listens on `http://localhost:8080`. On first run it creates `backend/data/` (H2 database
file) and `backend/uploads/` (stored PDFs) automatically. No API keys are required - `Ollama` and
`SearXNG` base URLs default to `http://localhost:11434` and `http://localhost:8888` respectively
(override with `OLLAMA_BASE_URL` / `SEARXNG_BASE_URL` / `OLLAMA_CHAT_MODEL` /
`OLLAMA_EMBEDDING_MODEL` if you run them elsewhere or want a different model size).

## Frontend setup

```bash
cd frontend
npm install
npm start
```

Opens on `http://localhost:4200`. The API base URL is set in
`frontend/src/app/core/api-config.ts` (defaults to `http://localhost:8080/api`).

## How the three quality scores are computed

- **Document Retrieval Confidence** - cosine similarity between your question's embedding and the
  best-matching chunk's embedding. Purely deterministic (`VectorMath`, `RagService`).
- **Document Fidelity** - cosine similarity between the embedding of the model's generated
  "documentClaim" and the embedding of the actual retrieved passages. Measures whether the
  explanation stayed faithful to the source text - not whether the source text is true.
- **Web Verification Confidence** - computed from source count, source authority tier (deterministic
  domain classification, not LLM-judged), agreement between sources, and recency
  (`VerdictCalculator`). The LLM only classifies each individual source's stance
  (supports/contradicts/mixed/not relevant); it is never asked to self-report a confidence number.

## Notes on scope

This is a Phase 1-3 MVP per the original spec: upload -> retrieve -> answer -> highlight evidence,
claim extraction -> web research -> verdict, and confidence scoring/caching are all implemented and
verified working end-to-end with the local Ollama + SearXNG stack. Not implemented (would be next):
rate limiting, a proper migration tool in place of `ddl-auto: update`, and swapping H2/local disk
storage for Postgres/S3 if this needs to run beyond a single machine.
