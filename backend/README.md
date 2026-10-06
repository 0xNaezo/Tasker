# Tasker AI proxy (`backend`)

A small Ktor service for **Google Play builds** (tech plan §18.1). It exposes typed routes rather than a
general-purpose LLM proxy. Prompts, schemas and answer validation come from `core/ai-contract`, and the
model call goes through OpenRouter with `core/ai-openrouter` ([ADR 0011](../docs/adr/0011-openrouter-provider.md)).
GitHub and Android Studio builds call OpenRouter directly with the user's own key and don't need this service.

PostgreSQL holds the install registry, daily limits, token and cost accounting, and weekly metric
aggregates. **Task texts are never stored or logged.**

## Routes

DTOs live in `core/ai-contract` (`ProxyProtocol.kt`, `EnrichModels.kt`). JSON bodies; `null` fields are omitted.

| Route | Auth | Request | Success |
| --- | --- | --- | --- |
| `GET /health` | none | — | `200 {"status":"ok"}`; `503` when the database is unreachable |
| `POST /v1/install` | Play Integrity token in the body (dev only: `X-Dev-Install-Key` header) | `InstallRequest` | `200 InstallResponse`: install token for 30 days |
| `POST /v1/enrich` | `Authorization: Bearer <install token>` | `EnrichRequest` (≤ 32 KiB) | `200 EnrichResponse`, already validated (sizes S/M/L, no past dates, fragments present in the text) |
| `POST /v1/metrics` | `Authorization: Bearer <install token>` | `MetricsReport` (≤ 16 KiB): weekly aggregates, numbers only | `204` |

Errors return an `ErrorBody {code, message}`. The message names fields only and never repeats request content.
After any error the app keeps its rule-based fields (AI-4, CAP-7).

| Status | `code` | What the app does |
| --- | --- | --- |
| 400 | `invalid_request` | Bug in the request; the message lists the invalid fields |
| 401 | `unauthorized` | Token missing, expired, rotated out, or the install is unknown: call `/v1/install` again |
| 403 | `integrity_failed`, `install_blocked`, `forbidden` | No AI through the proxy for this install |
| 413 | `payload_too_large` | Bug in the request |
| 422 | `refused` | The model declined this task: don't retry it |
| 429 | `daily_limit` | Retry after `Retry-After` (the next UTC day) |
| 502 | `invalid_output` | The answer was truncated or malformed: don't retry the same task |
| 502 | `upstream_error` | The provider rejected our call (key, billing, model settings): the operator gets an error log |
| 503 | `upstream_busy` | Provider overloaded, rate limited or unreachable: retry after `Retry-After` (30 s) |
| 503 | `budget_exhausted` | Daily budget spent: retry after `Retry-After` (the next UTC day) |
| 503 | `unavailable` | Play Integrity can't be checked right now: retry after `Retry-After` (60 s) |

## Configuration

All configuration comes from environment variables. Invalid values stop startup with a list of variable
names (never values) and exit code 78.

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `PORT` | | `8080` | HTTP port |
| `DATABASE_URL` | yes | | `jdbc:postgresql://…` or `postgres://user:password@host:port/db?sslmode=require` (the PaaS form) |
| `DATABASE_USER`, `DATABASE_PASSWORD` | | from the URL | Override the credentials in `DATABASE_URL` |
| `DATABASE_POOL_SIZE` | | `5` | Hikari pool size |
| `OPENROUTER_API_KEY` | yes | | OpenRouter API key (a PaaS secret) |
| `OPENROUTER_BASE_URL` | | `https://openrouter.ai/api/v1` | Another OpenRouter-compatible endpoint; `https://` only |
| `INSTALL_TOKEN_SECRET` | yes | | HMAC key for install tokens, at least 32 characters (`openssl rand -base64 48`) |
| `INSTALL_TOKEN_SECRET_PREVIOUS` | | | Previous key during rotation (see below) |
| `PLAY_PACKAGE_NAME` | prod | | Application id checked in Play Integrity verdicts. Without it, only the dev key can install |
| `GOOGLE_APPLICATION_CREDENTIALS_JSON` | prod | | Inline service-account JSON for the Play Integrity API |
| `GOOGLE_APPLICATION_CREDENTIALS` | | | Alternative: path to the service-account file (Application Default Credentials) |
| `DEV_INSTALL_KEY` | dev | | Dev environment only, at least 16 characters: replaces Play Integrity via `X-Dev-Install-Key`. **Never set in prod** |
| `DAILY_REQUESTS_PER_INSTALL` | | `200` | Requests per install per UTC day; failed calls count too |
| `DAILY_BUDGET_USD` | | `20` | Global provider spend per UTC day |
| `ROUTE_<ROUTE>_MODEL` | | `anthropic/claude-opus-5.5` | OpenRouter model id per route; `<ROUTE>` is `ENRICH`, `CLASSIFY`, `SPLIT`, `NEXT_STEP`, `SIMILAR` or `SUMMARIZE_SOURCE` |
| `ROUTE_<ROUTE>_EFFORT` | | `low` (`medium` for `SPLIT`, `SUMMARIZE_SOURCE`) | Reasoning effort: `low`, `medium`, `high`, `xhigh` or `max` |
| `ROUTE_<ROUTE>_MAX_TOKENS` | | `4096` (`8192` for `SPLIT`, `SUMMARIZE_SOURCE`) | Includes reasoning, which this model can't turn off |
| `ROUTE_<ROUTE>_ZDR` | | `true` | Only endpoints with zero data retention (`provider.zdr`). `false` lets the OpenRouter account's data policy decide: update the privacy policy first |

The MVP serves only `/v1/enrich`. Settings for the other routes are validated now so the later versions
(§17.3) can use them. A model change (§17.4, §17.6) takes a variable change and a restart, not an app release.
Every request also sets `provider.require_parameters`, so a model whose endpoints can't do strict structured
output (under the ZDR setting) fails with `upstream_error` instead of answering in another format.

## Run locally

Requirements: JDK 17+ and a PostgreSQL 14+ database.

```bash
docker run -d --name tasker-db -p 5432:5432 \
  -e POSTGRES_USER=tasker -e POSTGRES_PASSWORD=tasker -e POSTGRES_DB=tasker postgres:17

export DATABASE_URL=postgres://tasker:tasker@localhost:5432/tasker
export OPENROUTER_API_KEY=sk-or-...
export INSTALL_TOKEN_SECRET="$(openssl rand -base64 48)"
export DEV_INSTALL_KEY="$(openssl rand -hex 24)"
./gradlew :backend:run
```

Migrations run at startup (`src/main/resources/db/migration`, table `schema_version`). Keep local
variables in `backend/.env`; git ignores that file.

```bash
curl -s localhost:8080/health

TOKEN=$(curl -s localhost:8080/v1/install -H 'Content-Type: application/json' \
  -H "X-Dev-Install-Key: $DEV_INSTALL_KEY" \
  -d '{"installId":"3f2b6c1e-8a4d-4b7e-9c0f-1a2b3c4d5e6f","integrityToken":""}' | jq -r .token)

curl -s localhost:8080/v1/enrich -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{
  "text": "сдать отчёт до пятницы", "language": "ru",
  "now": "2026-10-06T13:00", "today": "2026-10-06", "timeZone": "Europe/Kyiv",
  "estimateScale": {"s": 15, "m": 60, "l": 180}, "extracted": {}, "similarDone": []
}'
```

Tests don't need Postgres, Docker or network access. They use H2 in PostgreSQL mode, a fake model and a fake
Play Integrity verifier:

```bash
./gradlew :backend:check
```

## Container and deploy

The image builds from the repository root, because the backend shares modules with the app. The build
stage doesn't need the Android SDK. The runtime image is `eclipse-temurin:21-jre` and runs as a non-root user.

```bash
docker build -f backend/Dockerfile -t tasker-backend .
docker run --rm -p 8080:8080 --env-file backend/.env tasker-backend
```

Environments (§24.1): CI builds the image. **dev** has `DEV_INSTALL_KEY` and may lack Play Integrity;
**prod** has `PLAY_PACKAGE_NAME` and Google credentials and no dev key. Prod deploys from tags.

- **Railway:**
  1. Create the service from the repository and set the service variable `RAILWAY_DOCKERFILE_PATH=backend/Dockerfile`.
  2. Add PostgreSQL and reference its `DATABASE_URL`.
  3. Set the variables above as secrets and set the health check path to `/health`.
- **Fly.io:**
  1. Run `fly launch --dockerfile backend/Dockerfile --no-deploy`, set `internal_port = 8080` and add an HTTP check on `/health`.
  2. Run `fly postgres attach`, which sets `DATABASE_URL`, then `fly secrets set OPENROUTER_API_KEY=… INSTALL_TOKEN_SECRET=…`.

Several instances can share one database: limits, budget and alerts live in it, and migrations take care
of concurrent starts.

## Play Integrity

1. In Play Console, link the app to a Google Cloud project (App integrity) and enable the Play Integrity API
   in that project.
2. Create a service account in the project and put its JSON key into `GOOGLE_APPLICATION_CREDENTIALS_JSON`.
3. The app requests a token with `requestHash` (standard API) or `nonce` (classic API) set to
   `IntegrityBinding.requestHash(installId)`, then sends it to `/v1/install`.
4. The backend decodes the token with `v1/{package}:decodeIntegrityToken` and accepts it only when all of
   these hold:
   - the app is `PLAY_RECOGNIZED` and both package names match;
   - the device has `MEETS_DEVICE_INTEGRITY` (or strong integrity);
   - the token is at most 10 minutes old;
   - the hash matches the install id.

   Rejections return 403 and log only a reason code. If Google can't be reached, or rejects our
   credentials, the response is 503 and the credential failure is logged as an error.

## Secrets and rotation

- **`OPENROUTER_API_KEY`:** create a new key in the OpenRouter dashboard, replace the secret, redeploy, then delete the old
  key. A credit limit on the key is a second safeguard next to `DAILY_BUDGET_USD`.
- **`INSTALL_TOKEN_SECRET`:**
  1. Move the current value to `INSTALL_TOKEN_SECRET_PREVIOUS` and set a new `INSTALL_TOKEN_SECRET`, then redeploy.
     New tokens use the new key; old tokens stay valid.
  2. After 30 days (the token lifetime), remove `INSTALL_TOKEN_SECRET_PREVIOUS`.

  Removing it earlier is harmless: apps get 401 and install again. Each token carries a key id derived from
  its secret.
- **`DEV_INSTALL_KEY`:** dev only. Rotate it when a tester leaves.
- **Google service account:** rotate the key in Google Cloud and replace `GOOGLE_APPLICATION_CREDENTIALS_JSON`.

## Limits, cost and alerts

- Each call reserves one request of the install's daily limit and checks the global daily budget. The cost
  is what OpenRouter reports for the call (`usage.cost`), recorded after the call; a call without it is priced
  from `core/ai-contract/.../ModelPricing.kt` (re-check those prices before launch). Concurrent in-flight
  calls can overshoot the budget by their own cost, which `max_tokens` bounds.
- A provider failure that nothing was billed for (408, 429, 5xx, timeout, no connection) is retried once
  within the call; each attempt has 30 seconds.
- Alerts are log lines with a stable prefix. Point the PaaS log alerts at them:
  - `ALERT ai_budget_warning` (WARN): 80% of the daily budget is spent. Fires once per day across instances.
  - `ALERT ai_budget_exhausted` (ERROR): the budget is spent and `/v1/enrich` answers 503 until the next UTC day. Fires once per day.
  - `ALERT ai_error_rate` (ERROR): at least 20% of the last 50 provider calls on an instance failed. Fires once per outage.
  - `Enrich call failed with AUTH` or `BAD_REQUEST` (ERROR): check the key, the OpenRouter credits, the model id
    and whether the model has endpoints that meet `ROUTE_<ROUTE>_ZDR`.
- Reports for the cost and monetisation decision (§17.5):

```sql
-- Spend and requests per day
SELECT cost_day, requests, cost_micro_usd / 1e6 AS cost_usd FROM global_daily_cost ORDER BY cost_day DESC LIMIT 30;

-- Error share and cache hit rate per day
SELECT usage_day,
       SUM(failed)::float / NULLIF(SUM(succeeded + refused + failed), 0) AS failed_share,
       SUM(cache_read_tokens)::float / NULLIF(SUM(input_tokens + cache_read_tokens + cache_creation_tokens), 0) AS cache_share
FROM daily_usage GROUP BY usage_day ORDER BY usage_day DESC LIMIT 30;

-- Cost per active install per day
SELECT usage_day, COUNT(*) AS installs, AVG(cost_micro_usd) / 1e6 AS avg_cost_usd
FROM daily_usage GROUP BY usage_day ORDER BY usage_day DESC LIMIT 30;
```

**Blocking an abusive install:** `UPDATE installs SET blocked = TRUE WHERE install_id = '…';`. Its calls
then answer 403, and so does `/v1/install`.

## Privacy

- Tables: `installs`, `daily_usage`, `global_daily_cost`, `metrics_weekly` and `schema_version`. None of
  them has a text column. Metric keys must match `[a-z][a-z0-9_.]{0,63}`, so free text can't be stored as a key.
- The access log has one line per request with method, route, status and latency. Model calls add the
  outcome, model, tokens and cost. Logs never include bodies, headers, query strings or install ids. Unknown
  paths are logged as `other`.
- Unexpected errors are logged with exception class names and stack frames only, because exception
  messages can quote request content.
- Task texts go to OpenRouter and from there to the model provider. With `ROUTE_<ROUTE>_ZDR=true` (the default)
  OpenRouter uses only endpoints with zero data retention; for `anthropic/claude-opus-5.5` with strict
  structured output these are Google Vertex AI endpoints. Keep logging of inputs and outputs off in the
  OpenRouter account's privacy settings, and reflect OpenRouter's and the providers' terms in the privacy policy.

## Known limitations and unverified parts

- Play Integrity has been tested only against a local fake of `decodeIntegrityToken`, not against Google.
- The Docker image hasn't been built here because no Docker daemon was available. The build step
  (`--configure-on-demand :backend:installDist` without the Android SDK) and the distribution were run
  locally against H2, before the switch to OpenRouter.
- OpenRouter calls are tested only against a fake (Ktor `MockEngine`), not against the real API.
- `/v1/install` has no rate limit per IP address. Every call with a token uses one unit of the Play
  Integrity API quota (10,000 a day by default). If the route is abused, enable rate limiting at the PaaS
  edge, or add Ktor's `RateLimit` plugin together with trusted `X-Forwarded-For` handling.
