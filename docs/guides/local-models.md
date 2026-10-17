# Local AI models with Ollama (AOS-703)

AdminOS can run its AI features on a model on your own machine, so nothing leaves it (D-67). Every call still goes through the API's AI layer (AOS-702): flags, redaction, the audit log and the eval gate apply.

## 1. Start Ollama (opt-in)
```bash
docker compose --profile ollama up -d ollama           # nothing starts it without the profile
docker compose exec ollama ollama pull qwen3:4b        # see "Which model" below; 1–3 GB per model
```
- Ollama has **no authentication**. It is published on `127.0.0.1:11434` only (the bake-off on your machine needs it). Never change it to a bare `11434:11434`: a test (`ComposeExposureTest`) fails on any bare host port.
- Models are kept in the `ollama_models` volume. An idle model is unloaded after `OLLAMA_KEEP_ALIVE` (default 10m).
- Docker on a Mac runs models on the CPU (no Metal GPU). That is fine for the background jobs AdminOS uses them for. For speed, run the native Ollama app instead and point `OLLAMA_BASE_URL` at it.

## 2. Measure models on the golden set (the bake-off)
```bash
cd services/api
./gradlew -q aiBakeOff -Pmodels=qwen3:1.7b,llama3.2:3b,qwen3:4b,gemma3:4b
```
- Each model answers the categorisation golden set (synthetic; only the 36 business names AOS-512 would ask about are sent, and only to your local Ollama) **through the same AI port as production**: same prompt, redaction and strict validation.
- The table compares, for each labeller:
  - the rules alone, rules + the recorded Claude answers, and rules + each model;
  - accuracy, macro-F1 and coverage;
  - rows labelled by the AI;
  - valid / rejected / unavailable answers;
  - agreement with Claude;
  - latency;
  - whether rule-decided rows were kept;
  - the gate.
- Each model's answers are written to `build/reports/ai-bakeoff/<model>.csv`.
- **Gate:** a model passes when it meets the rules baseline (D-61), never changes a row the rules decided, and gives a valid answer to at least 90% of questions.
- **Settings:**
  - `OLLAMA_BASE_URL` (default `http://127.0.0.1:11434`);
  - `OLLAMA_TIMEOUT_SECONDS` (default 120);
  - `OLLAMA_NUM_THREAD`, to use fewer CPU threads when the machine is busy (with every core fighting other work, generation can slow from tens of tokens a second to one every few seconds).

## 3. Turn a local model on
A feature only starts with a model that has a passing eval record (D-68):
```bash
./gradlew -q aiBakeOff -Pmodels=qwen3:4b -PwriteEvalRecord   # adds passing models to src/main/resources/ai/evals.json
```
Commit `evals.json`, rebuild the API, and set in `.env`:
```
AI_CATEGORIZATION_ENABLED=true
AI_CATEGORIZATION_PROVIDER=ollama
AI_CATEGORIZATION_MODEL=qwen3:4b
OLLAMA_BASE_URL=http://ollama:11434     # inside compose; http://127.0.0.1:11434 for an API run on the host
```
Local calls cost $0, so the monthly cap never blocks them; they are still audited.

## Which model
See the numbers and the recommendation in [AOS-703](../tickets/AOS-703.md#verification). Re-run the bake-off when you change models or the golden set grows.
