# TafakoriTech Observability Stack

Centralized log collection and visualization for all TafakoriTech services, running as a Docker Swarm stack.

**Components:**
- **Elasticsearch 8.13.4** — log storage and search
- **Kibana 8.13.4** — visualization and dashboards
- **Fluent Bit 3.0** — log collection from all Docker containers on every Swarm node

---

## Architecture

```
Docker containers (all Swarm nodes)
        │  /var/lib/docker/containers/*/*-json.log
        ▼
  Fluent Bit (global mode — one instance per node)
    • Parses Zap JSON logs
    • Redacts sensitive fields (password, token, secret, api_key)
    • Enriches with node hostname and Swarm node ID
    • Buffers to disk during ES downtime (up to 1 GB per node)
        │
        ▼ tafakoritech-logs-YYYY.MM.DD  (daily indices)
  Elasticsearch (single replica)
    • xpack security enabled
    • Credentials via Docker secrets (never in plaintext)
    • ILM policy: hot→warm→cold→delete lifecycle
        │
        ▼
  Kibana  →  http://127.0.0.1:9908
    • Login: elastic / <see Credentials section>
    • Pre-built data view, saved search, and 6-panel dashboard
```

All three services communicate over an internal overlay network (`obs-net`). Elasticsearch is **not** exposed on any host port — only Kibana's port 9908 is published.

---

## Credentials

All secrets are stored in Docker Swarm secrets (encrypted at rest). To read the current values:

```bash
# Elasticsearch superuser password
docker exec $(docker ps -q --filter name=observability_elasticsearch) \
  cat /run/secrets/elastic_password

# Kibana system user password (used internally — not for login)
grep KIBANA_SYSTEM_PASSWORD /home/mtafakori/projects/TafakoriTech/infra/observability/.env
```

| User | Role | Used by |
|------|------|---------|
| `elastic` | Superuser | Kibana login, setup scripts |
| `kibana_system` | Built-in | Kibana ↔ ES internal connection |
| `fluent_bit` | `fluent_bit_writer` | Fluent Bit → ES log shipping |

The `fluent_bit_writer` role grants only `create_index`, `write`, and `auto_configure` on `tafakoritech-*` indices — no read access.

---

## Deploy

### First deploy

```bash
cd /home/mtafakori/projects/TafakoriTech/infra/observability
bash deploy.sh
```

Wait ~30 seconds for Elasticsearch to initialize, then run setup to create ES users, ILM policy, index template, and Kibana data views/dashboard:

```bash
bash setup.sh
```

### Redeploy (config changes)

Always use `deploy.sh` — it handles the Docker config immutability limitation by removing stale configs before redeploying:

```bash
bash deploy.sh
```

Do **not** use `docker stack deploy` directly; Fluent Bit configs cannot be updated in place.

---

## Kibana Usage

Open **http://127.0.0.1:9908** and log in as `elastic`.

### Discover — search raw logs

1. Click **Discover** in the left sidebar
2. Select the `tafakoritech-logs-*` data view
3. Set a time range (top right)
4. Use KQL to filter, e.g.:
   ```
   service: "sentinel" and level: "error"
   trace_id: "abc123"
   status_code >= 500
   method: "POST" and path: "/api/auth/login"
   ```

### Dashboard

**Dashboards → TafakoriTech — Observability Overview**

| Panel | Shows |
|-------|-------|
| Log Volume Over Time | Total log count per time bucket |
| Logs by Service | Distribution across sentinel / persona / herald / marshal |
| Error Rate by Service | `level: error` count per service over time |
| HTTP Status Code Distribution | 2xx / 4xx / 5xx breakdown |
| p99 Latency Trend | 99th-percentile `latency_ms` over time |
| Log Level Distribution | debug / info / warn / error counts |

### Saved Search

**Discover → Open → "All TafakoriTech Logs"** — pre-configured with columns: `@timestamp`, `service`, `level`, `message`, `method`, `path`, `status_code`.

---

## Index Lifecycle

| Phase | Triggered at | Action |
|-------|-------------|--------|
| Hot | Immediately | Rollover after 7 days or 50 GB |
| Warm | 7 days | Read-only, force-merge to 1 segment, shrink to 1 shard |
| Cold | 30 days | Lowest priority |
| Delete | 90 days | Index permanently removed |

---

## Log Format

Fluent Bit expects structured JSON logs (Zap format) from each service. Example:

```json
{
  "@timestamp": "2026-06-13T14:22:01Z",
  "level": "info",
  "service": "sentinel",
  "message": "request handled",
  "method": "POST",
  "path": "/api/auth/login",
  "status_code": 200,
  "latency_ms": 12,
  "trace_id": "a1b2c3d4",
  "user_id": "u-999"
}
```

Fields automatically added by Fluent Bit:
- `swarm_hostname` — Swarm node name
- `swarm_node_id` — Swarm node ID
- `container_id` — Docker container ID

Fields **redacted before shipping** (never reach Elasticsearch):
`password`, `token`, `secret`, `api_key`, `authorization`, `x-api-key`

---

## Useful Commands

```bash
# Check all service replicas
docker stack services observability

# Tail Fluent Bit output (filtered)
docker service logs observability_fluent-bit -f 2>&1 | grep -v backlog

# Check Kibana health
curl -sf -u elastic:<password> http://127.0.0.1:9908/api/status | grep -o '"level":"[^"]*"'

# Count indexed documents
docker exec $(docker ps -q --filter name=observability_elasticsearch) \
  bash -c 'curl -sf -u "elastic:$(cat /run/secrets/elastic_password)" \
  "http://127.0.0.1:9200/tafakoritech-logs/_count"'

# List indices with doc counts
docker exec $(docker ps -q --filter name=observability_elasticsearch) \
  bash -c 'curl -sf -u "elastic:$(cat /run/secrets/elastic_password)" \
  "http://127.0.0.1:9200/_cat/indices/tafakoritech-*?v&h=index,docs.count,store.size,health"'

# Scale down / remove stack
docker stack rm observability
```

---

## Files

```
observability/
├── deploy.sh               # deploy / redeploy script
├── setup.sh                # post-deploy ES + Kibana provisioning
├── stack.yml               # Docker Swarm stack definition
├── .env                    # non-sensitive deploy-time config
├── elasticsearch/
│   ├── elasticsearch.yml   # ES node config (xpack, cluster, paths)
│   ├── ilm-policy.json     # index lifecycle policy (hot→warm→cold→delete)
│   └── index-template.json # index template for tafakoritech-logs-*
├── fluent-bit/
│   ├── fluent-bit.conf     # pipeline: tail → parse → filter → ES output
│   ├── parsers.conf        # docker + zap_json parsers
│   └── extract_meta.lua    # Lua filter: lifts container_id from tag
└── kibana/
    └── kibana.yml          # Kibana server config
```
