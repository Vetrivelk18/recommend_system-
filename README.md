# Basket

A grocery storefront with hybrid product search and LLM-reranked recommendations, built
to stay up when its dependencies do not.

**Live demo:** https://basket-160251076378.asia-northeast1.run.app

The demo runs on free tiers and scales to zero, so the first page load after an idle
spell takes 10-15 seconds while the app starts.

## What it does

- **Hybrid search** over ~50,000 products: a semantic arm (384-d BGE-small embeddings in
  pgvector) and a lexical arm (Postgres full-text search), fused with Reciprocal Rank Fusion.
- **Recommendations**: candidates gathered around the shopper's latest search and purchase
  history, reranked to a top five by Gemini.
- **Storefront**: signup with onboarding preferences, JWT login, dashboard, cart, orders.
- **Per-user and per-IP rate limiting** on search, recommendations and auth.

## Architecture

```
Browser (Angular)
      |
      v
Spring Boot  ---------------------------  one jar serves the API and the Angular build
      |
      |-- Supabase Postgres + pgvector    products, embeddings, users, carts, orders
      |-- Upstash Redis                   search cache, recommendation cache, rate limits
      |-- CloudAMQP RabbitMQ              background job that pre-warms recommendations
      |-- Gemini API                      final rerank of recommendation candidates
      `-- Embed service (Python)          query text -> 384-d vector (fastembed, BGE-small)
```

Both the Spring app and the embed service run on Google Cloud Run, each capped at one
instance. Everything else is a managed free tier.

### Search flow

```
query --> embed service --> vector
                              |
        +---------------------+----------------------+
        v                                            v
  semantic arm: text_emb <=> vector        lexical arm: name_tsv @@ query
  (top 100)                                (top 100)
        +---------------------+----------------------+
                              v
                  Reciprocal Rank Fusion --> 50 results, cached 10 min
```

Rank fusion is used because cosine distance and `ts_rank` are not on comparable scales;
fusing by rank avoids normalising scores. There is deliberately no vector index: at this
catalogue size exact KNN gives perfect recall and handles the `in_stock` filter cleanly.

### Recommendation flow

A signed-in search publishes a job to RabbitMQ. The consumer gathers candidates, asks
Gemini to pick five, and caches the result for 30 minutes, so the recommendations page
usually reads from cache. If the job has not run, the page computes on demand.

## Failure behaviour

Every external dependency except the database is optional at runtime. Each row below was
tested against the deployed service by breaking that dependency on a separate revision.

| Dependency down | What the user sees | What happens |
|---|---|---|
| Embed service | Normal page, weaker ranking | Search falls back to lexical-only; the degraded result is not cached |
| Redis | Normal page, slower | Cache reads count as misses; rate limiting fails open |
| RabbitMQ | Normal page | Publish fails quietly; recommendations compute on demand |
| Gemini | Normal page, unranked picks | Candidates are returned in their existing order |
| Postgres | Error page | The app cannot start without its database |

Auth failures return `401` (missing, malformed or tampered token) or `403` (a valid token
used on another user's data). Rate limits return `429` with `Retry-After`.

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 4.1, Spring Data JPA, Spring AMQP |
| Frontend | Angular 22 |
| Database | PostgreSQL (Supabase) with pgvector and full-text search |
| Cache / rate limits | Redis (Upstash) |
| Queue | RabbitMQ (CloudAMQP) |
| Embeddings | Python, fastembed, `BAAI/bge-small-en-v1.5` |
| Reranker | Gemini (hosted) or Ollama (local), selected by property |
| Hosting | Google Cloud Run, Secret Manager |

## Running locally

You need Java 17, Python 3.9+, and a Postgres database with the product data loaded.
Redis and RabbitMQ are optional: without them the app still runs, uncached.

```bash
# 1. Embed service (port 8001)
cd ml
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python embed_service.py

# 2. Spring app (port 8080), from the project root
export SUPABASE_DB_PASSWORD=...        # required
export JWT_SECRET=...                  # required, at least 32 characters
export RERANKER_PROVIDER=gemini        # default is a local Ollama
export GEMINI_API_KEY=...
export GEMINI_MODEL=gemini-3.8-flash
./mvnw spring-boot:run
```

Then open http://localhost:8080. To work on the frontend with live reload, run
`npm install && npm start` in `frontend/`; it proxies `/api` to port 8080.

Optional settings, all read from the environment: `EMBED_BASE_URL`, `EMBED_API_KEY`,
`REDIS_HOST` / `REDIS_PORT` / `REDIS_USERNAME` / `REDIS_PASSWORD` / `REDIS_SSL`,
`RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` /
`RABBITMQ_VHOST` / `RABBITMQ_SSL`. See `application.properties` for what each one does.

## Deploying

Each service has its own Dockerfile and deploys from source:

```bash
gcloud run deploy basket-embed --source ml --region asia-northeast1 --max-instances 1
gcloud run deploy basket       --source .  --region asia-northeast1 --max-instances 1
```

Credentials are held in Secret Manager and attached with `--set-secrets`; none are in the
repository. The embedding model is downloaded when the image is built, so a cold start
only has to load it from disk.

## Project layout

```
src/main/java/com/example/   Spring Boot application
src/main/resources/static/   compiled Angular app, served by Spring
frontend/                    Angular source
ml/embed_service.py          embedding HTTP service
ml/                          offline scripts: product embedding, two-tower training
schema/                      SQL for cart and order tables
RATE_LIMITING.md             how the rate limiter works, file by file
```

## Known limitations

- An uncached search takes several seconds; cached searches return in under a second.
- On Cloud Run the app only has CPU while handling a request, so the RabbitMQ consumer and
  scheduled jobs are best-effort when the site is idle.
- The Gemini free tier is rate-limited and sometimes returns "high demand"; those requests
  fall back to unranked candidates.
- The database's connection pooler allows 15 connections, which limits how many app
  instances can run at once.
