#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# TafakoriTech Observability Setup
# Run AFTER the stack is deployed.  Creates ILM policy, index template,
# Kibana data view, and dashboard visualizations.
#
# Usage:
#   ELASTIC_PASSWORD=<password> ./setup.sh
#   ./setup.sh   # reads from Docker secret at /run/secrets/elastic_password
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
ES_HOST="${ES_HOST:-127.0.0.1}"
ES_PORT="${ES_PORT:-9200}"
KB_HOST="${KB_HOST:-127.0.0.1}"
KB_PORT="${KB_PORT:-9908}"
ES_USER="${ES_USER:-elastic}"

if [[ -z "${ELASTIC_PASSWORD:-}" ]]; then
    if [[ -f /run/secrets/elastic_password ]]; then
        ELASTIC_PASSWORD=$(cat /run/secrets/elastic_password)
    elif [[ -f "${SCRIPT_DIR:-.}/.env" ]]; then
        ELASTIC_PASSWORD=$(grep '^ELASTIC_PASSWORD=' "${SCRIPT_DIR:-.}/.env" | cut -d= -f2-)
    fi
fi

if [[ -z "${ELASTIC_PASSWORD:-}" ]]; then
    # Read from Docker secret via a temporary container
    ELASTIC_PASSWORD=$(docker run --rm --secret elastic_password alpine:3.19 \
        sh -c 'cat /run/secrets/elastic_password' 2>/dev/null) || true
fi

if [[ -z "${ELASTIC_PASSWORD:-}" ]]; then
    echo "ERROR: Could not determine ELASTIC_PASSWORD. Set it via env var or Docker secret." >&2
    exit 1
fi

# Fluent Bit dedicated ES user credentials (from .env)
FLUENT_BIT_ES_USER="${FLUENT_BIT_ES_USER:-fluent_bit}"
FLUENT_BIT_ES_PASSWORD="${FLUENT_BIT_ES_PASSWORD:-}"
if [[ -z "$FLUENT_BIT_ES_PASSWORD" && -f "${SCRIPT_DIR:-.}/.env" ]]; then
    FLUENT_BIT_ES_PASSWORD=$(grep '^FLUENT_BIT_ES_PASSWORD=' "${SCRIPT_DIR:-.}/.env" | cut -d= -f2-)
fi

ES_URL="http://${ES_HOST}:${ES_PORT}"
KB_URL="http://${KB_HOST}:${KB_PORT}"
AUTH="${ES_USER}:${ELASTIC_PASSWORD}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[setup]${NC} $*"; }
warn() { echo -e "${YELLOW}[setup]${NC} $*"; }
err()  { echo -e "${RED}[setup]${NC} $*" >&2; }

# ── Helpers ────────────────────────────────────────────────────────────────────
wait_for() {
    local url="$1" label="$2" max_attempts="${3:-30}" attempt=0
    log "Waiting for $label at $url …"
    until curl -s -u "$AUTH" "$url" >/dev/null 2>&1; do
        attempt=$(( attempt + 1 ))
        if (( attempt >= max_attempts )); then
            err "$label did not become available after ${max_attempts}s"
            exit 1
        fi
        sleep 5
    done
    log "$label is ready"
}

es_put() {
    local path="$1" data="$2"
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
        -X PUT "${ES_URL}${path}" \
        -H "Content-Type: application/json" \
        -d "$data")
    if [[ "$code" != "200" && "$code" != "201" ]]; then
        err "ES PUT ${path} returned HTTP $code"
        return 1
    fi
}

kb_post() {
    local path="$1" data="$2"
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
        -X POST "${KB_URL}${path}" \
        -H "kbn-xsrf: true" \
        -H "Content-Type: application/json" \
        -d "$data")
    if [[ "$code" != "200" && "$code" != "201" ]]; then
        err "Kibana POST ${path} returned HTTP $code"
        return 1
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
# 1. Elasticsearch — wait and verify
# ─────────────────────────────────────────────────────────────────────────────
wait_for "${ES_URL}/_cluster/health" "Elasticsearch" 60

CLUSTER_STATUS=$(curl -s -u "$AUTH" "${ES_URL}/_cluster/health" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
if [[ "$CLUSTER_STATUS" == "red" ]]; then
    err "Elasticsearch cluster is RED — aborting"
    exit 1
fi
log "Cluster status: $CLUSTER_STATUS"

# ─────────────────────────────────────────────────────────────────────────────
# 2. Create dedicated fluent_bit ES user (write-only to tafakoritech-* indices)
# ─────────────────────────────────────────────────────────────────────────────
# Reset kibana_system password (ES 8.x requires kibana_system, not elastic)
KIBANA_SYSTEM_PASSWORD="${KIBANA_SYSTEM_PASSWORD:-}"
if [[ -z "$KIBANA_SYSTEM_PASSWORD" && -f "${SCRIPT_DIR:-.}/.env" ]]; then
    KIBANA_SYSTEM_PASSWORD=$(grep '^KIBANA_SYSTEM_PASSWORD=' "${SCRIPT_DIR:-.}/.env" | cut -d= -f2-)
fi
if [[ -n "$KIBANA_SYSTEM_PASSWORD" ]]; then
    log "Resetting kibana_system user password"
    curl -sf -u "$AUTH" -X POST "${ES_URL}/_security/user/kibana_system/_password" \
        -H "Content-Type: application/json" \
        -d "{\"password\": \"${KIBANA_SYSTEM_PASSWORD}\"}" >/dev/null
    log "  kibana_system password set"
fi

if [[ -n "$FLUENT_BIT_ES_PASSWORD" ]]; then
    log "Creating fluent_bit role and user"

    # Role: write access to tafakoritech-* only
    curl -sf -u "$AUTH" -X PUT "${ES_URL}/_security/role/fluent_bit_writer" \
        -H "Content-Type: application/json" \
        -d '{
            "cluster": ["monitor"],
            "indices": [{
                "names": ["tafakoritech-*"],
                "privileges": ["create_index", "write", "auto_configure"]
            }]
        }' | grep -q '"created"' && log "  role created" || log "  role updated"

    # User: fluent_bit with the write role
    curl -sf -u "$AUTH" -X PUT "${ES_URL}/_security/user/${FLUENT_BIT_ES_USER}" \
        -H "Content-Type: application/json" \
        -d "{
            \"password\": \"${FLUENT_BIT_ES_PASSWORD}\",
            \"roles\": [\"fluent_bit_writer\"],
            \"full_name\": \"Fluent Bit Log Shipper\"
        }" | grep -q '"created"' && log "  user created" || log "  user updated"
else
    warn "FLUENT_BIT_ES_PASSWORD not set — skipping fluent_bit user creation"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 2. ILM policy
# ─────────────────────────────────────────────────────────────────────────────
log "Creating ILM policy: tafakoritech-logs-policy"
es_put "/_ilm/policy/tafakoritech-logs-policy" \
    "$(cat "${SCRIPT_DIR}/elasticsearch/ilm-policy.json")"

# ─────────────────────────────────────────────────────────────────────────────
# 3. Index template
# ─────────────────────────────────────────────────────────────────────────────
log "Creating index template: tafakoritech-logs"
es_put "/_index_template/tafakoritech-logs" \
    "$(cat "${SCRIPT_DIR}/elasticsearch/index-template.json")"

# ─────────────────────────────────────────────────────────────────────────────
# 4. Bootstrap write alias (Fluent Bit creates daily indices directly,
#    but we pre-create today's index so ILM rollover works correctly)
# ─────────────────────────────────────────────────────────────────────────────
TODAY=$(date -u +%Y.%m.%d)
INITIAL_INDEX="tafakoritech-logs-${TODAY}-000001"
log "Bootstrapping initial index: ${INITIAL_INDEX}"
curl -s -u "$AUTH" -X PUT "${ES_URL}/${INITIAL_INDEX}" \
    -H "Content-Type: application/json" \
    -d '{
        "aliases": {
            "tafakoritech-logs": { "is_write_index": true }
        }
    }' | grep -q '"acknowledged":true' || warn "Initial index may already exist — skipping"

# ─────────────────────────────────────────────────────────────────────────────
# 5. Kibana — wait
# ─────────────────────────────────────────────────────────────────────────────
wait_for "${KB_URL}/api/status" "Kibana" 60

# ─────────────────────────────────────────────────────────────────────────────
# 6. Kibana data view (index pattern)
# ─────────────────────────────────────────────────────────────────────────────
log "Creating Kibana data view: tafakoritech-logs-*"
kb_post "/api/data_views/data_view" '{
    "data_view": {
        "id": "tafakoritech-logs",
        "title": "tafakoritech-logs-*",
        "timeFieldName": "@timestamp",
        "name": "TafakoriTech Logs"
    },
    "override": true
}'

# ─────────────────────────────────────────────────────────────────────────────
# 7. Kibana saved searches
# ─────────────────────────────────────────────────────────────────────────────
log "Creating saved searches"

create_search() {
    local id="$1" title="$2" query="$3" columns="$4"
    kb_post "/api/saved_objects/search/${id}?overwrite=true" "$(cat <<EOF
{
    "attributes": {
        "title": "${title}",
        "description": "",
        "columns": ${columns},
        "sort": [["@timestamp", "desc"]],
        "kibanaSavedObjectMeta": {
            "searchSourceJSON": "{\"index\":\"tafakoritech-logs\",\"query\":{\"query\":\"${query}\",\"language\":\"kuery\"},\"filter\":[]}"
        }
    },
    "references": [{"name":"kibanaSavedObjectMeta.searchSourceJSON.index","type":"index-pattern","id":"tafakoritech-logs"}]
}
EOF
)"
}

create_search "tafakoritech-errors"      "All Errors"          "level:error"     '["@timestamp","service","level","message","trace_id","request_id","error"]'
create_search "tafakoritech-all-requests" "All HTTP Requests"  "method:*"        '["@timestamp","service","method","path","status_code","duration_ms","trace_id","ip"]'
create_search "tafakoritech-slow-req"    "Slow Requests >1s"   "duration_ms:>1000" '["@timestamp","service","method","path","status_code","duration_ms","user_id"]'
create_search "tafakoritech-5xx"         "5xx Errors"          "status_code:>=500" '["@timestamp","service","method","path","status_code","trace_id","error"]'

# ─────────────────────────────────────────────────────────────────────────────
# 8. Kibana Lens visualizations
# ─────────────────────────────────────────────────────────────────────────────
log "Creating Lens visualizations"

create_lens() {
    local id="$1" json="$2"
    kb_post "/api/saved_objects/lens/${id}?overwrite=true" "$json"
}

# 8a. Requests per minute (line chart over time)
create_lens "tafakoritech-requests-per-min" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Requests per Minute",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["time_col","count_col"],
              "columns": {
                "time_col": {
                  "label": "@timestamp",
                  "dataType": "date",
                  "operationType": "date_histogram",
                  "sourceField": "@timestamp",
                  "isBucketed": true,
                  "params": {"interval": "auto", "dropPartials": false, "includeEmptyRows": true}
                },
                "count_col": {
                  "label": "Requests",
                  "dataType": "number",
                  "operationType": "count",
                  "isBucketed": false
                }
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "line",
        "layers": [{"layerId":"main","seriesType":"line","xAccessor":"time_col","accessors":["count_col"],"layerType":"data"}],
        "legend": {"isVisible": true, "position": "right"},
        "valueLabels": "hide",
        "fittingFunction": "None"
      },
      "query": {"query": "method:*", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# 8b. Errors by service (horizontal bar)
create_lens "tafakoritech-errors-by-service" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Errors by Service",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["service_col","count_col"],
              "columns": {
                "service_col": {
                  "label": "Service",
                  "dataType": "string",
                  "operationType": "terms",
                  "sourceField": "service",
                  "isBucketed": true,
                  "params": {"size": 10, "orderBy": {"type":"column","columnId":"count_col"}, "orderDirection":"desc", "parentFormat":{"id":"terms"}, "include":[], "exclude":[]}
                },
                "count_col": {
                  "label": "Error Count",
                  "dataType": "number",
                  "operationType": "count",
                  "isBucketed": false
                }
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "bar_horizontal",
        "layers": [{"layerId":"main","seriesType":"bar_horizontal","xAccessor":"count_col","accessors":["service_col"],"layerType":"data"}],
        "legend": {"isVisible": false}
      },
      "query": {"query": "level:error", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# 8c. Top endpoints by request count
create_lens "tafakoritech-top-endpoints" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Top Endpoints",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["path_col","count_col"],
              "columns": {
                "path_col": {
                  "label": "Path",
                  "dataType": "string",
                  "operationType": "terms",
                  "sourceField": "path",
                  "isBucketed": true,
                  "params": {"size": 20, "orderBy": {"type":"column","columnId":"count_col"}, "orderDirection":"desc", "parentFormat":{"id":"terms"}, "include":[], "exclude":[]}
                },
                "count_col": {
                  "label": "Requests",
                  "dataType": "number",
                  "operationType": "count",
                  "isBucketed": false
                }
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "bar_horizontal",
        "layers": [{"layerId":"main","seriesType":"bar_horizontal","xAccessor":"count_col","accessors":["path_col"],"layerType":"data"}],
        "legend": {"isVisible": false}
      },
      "query": {"query": "method:*", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# 8d. Average response time per service
create_lens "tafakoritech-avg-response-time" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Avg Response Time (ms) by Service",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["time_col","avg_col"],
              "columns": {
                "time_col": {
                  "label": "@timestamp",
                  "dataType": "date",
                  "operationType": "date_histogram",
                  "sourceField": "@timestamp",
                  "isBucketed": true,
                  "params": {"interval": "auto", "dropPartials": false, "includeEmptyRows": true}
                },
                "avg_col": {
                  "label": "Avg duration (ms)",
                  "dataType": "number",
                  "operationType": "average",
                  "sourceField": "duration_ms",
                  "isBucketed": false
                }
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "line",
        "layers": [{"layerId":"main","seriesType":"line","xAccessor":"time_col","accessors":["avg_col"],"layerType":"data"}],
        "legend": {"isVisible": true, "position": "right"},
        "fittingFunction": "None"
      },
      "query": {"query": "duration_ms:*", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# 8e. HTTP 5xx error rate over time
create_lens "tafakoritech-error-rate" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Error Rate (5xx) Over Time",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["time_col","count_col"],
              "columns": {
                "time_col": {
                  "label": "@timestamp",
                  "dataType": "date",
                  "operationType": "date_histogram",
                  "sourceField": "@timestamp",
                  "isBucketed": true,
                  "params": {"interval": "auto", "dropPartials": false, "includeEmptyRows": true}
                },
                "count_col": {
                  "label": "5xx Errors",
                  "dataType": "number",
                  "operationType": "count",
                  "isBucketed": false
                }
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "area",
        "layers": [{"layerId":"main","seriesType":"area","xAccessor":"time_col","accessors":["count_col"],"layerType":"data"}],
        "legend": {"isVisible": true},
        "fittingFunction": "None"
      },
      "query": {"query": "status_code >= 500", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# 8f. Logs by node/host
create_lens "tafakoritech-logs-by-node" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Log Volume by Node",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["time_col","node_col","count_col"],
              "columns": {
                "time_col": {
                  "label": "@timestamp",
                  "dataType": "date",
                  "operationType": "date_histogram",
                  "sourceField": "@timestamp",
                  "isBucketed": true,
                  "params": {"interval": "auto", "dropPartials": false, "includeEmptyRows": true}
                },
                "node_col": {
                  "label": "Node",
                  "dataType": "string",
                  "operationType": "terms",
                  "sourceField": "swarm_hostname",
                  "isBucketed": true,
                  "params": {"size": 10, "orderBy": {"type":"column","columnId":"count_col"}, "orderDirection":"desc", "parentFormat":{"id":"terms"}, "include":[], "exclude":[]}
                },
                "count_col": {
                  "label": "Log Lines",
                  "dataType": "number",
                  "operationType": "count",
                  "isBucketed": false
                }
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "bar_stacked",
        "layers": [{"layerId":"main","seriesType":"bar_stacked","xAccessor":"time_col","accessors":["count_col"],"splitAccessor":"node_col","layerType":"data"}],
        "legend": {"isVisible": true, "position": "right"},
        "fittingFunction": "None"
      },
      "query": {"query": "", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# 9. Kibana Dashboard
# ─────────────────────────────────────────────────────────────────────────────
log "Creating main dashboard"

kb_post "/api/saved_objects/dashboard/tafakoritech-main?overwrite=true" "$(cat <<'EOF'
{
  "attributes": {
    "title": "TafakoriTech — Observability Overview",
    "description": "Request metrics, error rates, and log volume across all microservices",
    "panelsJSON": "[{\"panelIndex\":\"1\",\"gridData\":{\"x\":0,\"y\":0,\"w\":24,\"h\":12,\"i\":\"1\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_1\"},{\"panelIndex\":\"2\",\"gridData\":{\"x\":24,\"y\":0,\"w\":24,\"h\":12,\"i\":\"2\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_2\"},{\"panelIndex\":\"3\",\"gridData\":{\"x\":0,\"y\":12,\"w\":24,\"h\":12,\"i\":\"3\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_3\"},{\"panelIndex\":\"4\",\"gridData\":{\"x\":24,\"y\":12,\"w\":24,\"h\":12,\"i\":\"4\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_4\"},{\"panelIndex\":\"5\",\"gridData\":{\"x\":0,\"y\":24,\"w\":24,\"h\":12,\"i\":\"5\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_5\"},{\"panelIndex\":\"6\",\"gridData\":{\"x\":24,\"y\":24,\"w\":24,\"h\":12,\"i\":\"6\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_6\"}]",
    "optionsJSON": "{\"useMargins\":true,\"syncColors\":false,\"hidePanelTitles\":false}",
    "timeRestore": false,
    "kibanaSavedObjectMeta": {
      "searchSourceJSON": "{\"query\":{\"query\":\"\",\"language\":\"kuery\"},\"filter\":[]}"
    }
  },
  "references": [
    {"name":"panel_1","type":"lens","id":"tafakoritech-requests-per-min"},
    {"name":"panel_2","type":"lens","id":"tafakoritech-errors-by-service"},
    {"name":"panel_3","type":"lens","id":"tafakoritech-top-endpoints"},
    {"name":"panel_4","type":"lens","id":"tafakoritech-avg-response-time"},
    {"name":"panel_5","type":"lens","id":"tafakoritech-error-rate"},
    {"name":"panel_6","type":"lens","id":"tafakoritech-logs-by-node"}
  ]
}
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────────────────────────────────────
echo ""
log "✓ Setup complete"
echo ""
echo "  Kibana:        http://${KB_HOST}:${KB_PORT}"
echo "  Dashboard:     http://${KB_HOST}:${KB_PORT}/app/dashboards#/view/tafakoritech-main"
echo "  Discover:      http://${KB_HOST}:${KB_PORT}/app/discover"
echo "  ES Health:     ${ES_URL}/_cluster/health?pretty"
echo ""
warn "Remember: run 'docker stack rm observability && docker stack deploy' to pick up config changes."
