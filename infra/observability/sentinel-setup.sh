#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Sentinel Auth Service — Kibana dashboards setup
# Run AFTER the main observability setup.sh has completed.
#
# Usage:
#   ELASTIC_PASSWORD=<password> ./sentinel-setup.sh
#   ./sentinel-setup.sh   # reads from Docker secret at /run/secrets/elastic_password
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ES_HOST="${ES_HOST:-127.0.0.1}"
ES_PORT="${ES_PORT:-9200}"
KB_HOST="${KB_HOST:-127.0.0.1}"
KB_PORT="${KB_PORT:-9908}"
ES_USER="${ES_USER:-elastic}"

if [[ -z "${ELASTIC_PASSWORD:-}" ]]; then
    if [[ -f /run/secrets/elastic_password ]]; then
        ELASTIC_PASSWORD=$(cat /run/secrets/elastic_password)
    elif [[ -f "${SCRIPT_DIR}/.env" ]]; then
        ELASTIC_PASSWORD=$(grep '^ELASTIC_PASSWORD=' "${SCRIPT_DIR}/.env" | cut -d= -f2-)
    fi
fi
if [[ -z "${ELASTIC_PASSWORD:-}" ]]; then
    ELASTIC_PASSWORD=$(docker run --rm --secret elastic_password alpine:3.19 \
        sh -c 'cat /run/secrets/elastic_password' 2>/dev/null) || true
fi
if [[ -z "${ELASTIC_PASSWORD:-}" ]]; then
    echo "ERROR: ELASTIC_PASSWORD not set." >&2
    exit 1
fi

AUTH="${ES_USER}:${ELASTIC_PASSWORD}"
ES_URL="http://${ES_HOST}:${ES_PORT}"
KB_URL="http://${KB_HOST}:${KB_PORT}"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[sentinel-setup]${NC} $*"; }
warn() { echo -e "${YELLOW}[sentinel-setup]${NC} $*"; }
err()  { echo -e "${RED}[sentinel-setup]${NC} $*" >&2; }

wait_for() {
    local url="$1" label="$2" max="${3:-30}" n=0
    log "Waiting for $label …"
    until curl -s -u "$AUTH" "$url" >/dev/null 2>&1; do
        n=$(( n + 1 ))
        [[ $n -ge $max ]] && { err "$label not ready after ${max}s"; exit 1; }
        sleep 5
    done
    log "$label is ready"
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

create_lens() {
    local id="$1" json="$2"
    kb_post "/api/saved_objects/lens/${id}?overwrite=true" "$json"
}

# ─── Wait ────────────────────────────────────────────────────────────────────
wait_for "${KB_URL}/api/status" "Kibana" 60

# ─── Grant fluent_bit_writer access to audit + app log fields ────────────────
# The existing role is already scoped to tafakoritech-* so no change needed.

# ─── Update ILM: extend audit log retention to 365 days ─────────────────────
log "Extending ILM for sentinel (audit logs need 365d retention)"
code=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    -X PUT "${ES_URL}/_ilm/policy/tafakoritech-logs-policy" \
    -H "Content-Type: application/json" \
    -d '{
        "policy": {
            "phases": {
                "hot":  { "min_age": "0ms", "actions": { "set_priority": {"priority":100}, "rollover": {"max_age":"7d","max_primary_shard_size":"50gb"} } },
                "warm": { "min_age": "7d",  "actions": { "set_priority": {"priority":50},  "readonly": {}, "forcemerge": {"max_num_segments":1}, "shrink": {"number_of_shards":1} } },
                "cold": { "min_age": "90d", "actions": { "set_priority": {"priority":0} } },
                "delete": { "min_age": "365d", "actions": { "delete": {"delete_searchable_snapshot":true} } }
            }
        }
    }')
[[ "$code" == "200" ]] && log "  ILM policy updated" || warn "  ILM update returned $code (may already exist)"

# ─── Lens: OTP verification success/failure rate ─────────────────────────────
log "Creating Lens: OTP verification rate"
create_lens "sentinel-otp-verify-rate" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Sentinel — OTP Verification Rate",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["time_col","result_col","count_col"],
              "columns": {
                "time_col":   {"label":"Time","dataType":"date","operationType":"date_histogram","sourceField":"@timestamp","isBucketed":true,"params":{"interval":"auto","dropPartials":false,"includeEmptyRows":true}},
                "result_col": {"label":"Result","dataType":"string","operationType":"terms","sourceField":"result","isBucketed":true,"params":{"size":5,"orderBy":{"type":"column","columnId":"count_col"},"orderDirection":"desc","parentFormat":{"id":"terms"},"include":[],"exclude":[]}},
                "count_col":  {"label":"Count","dataType":"number","operationType":"count","isBucketed":false}
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "bar_stacked",
        "layers": [{"layerId":"main","seriesType":"bar_stacked","xAccessor":"time_col","accessors":["count_col"],"splitAccessor":"result_col","layerType":"data"}],
        "legend": {"isVisible":true,"position":"right"},
        "fittingFunction": "None"
      },
      "query": {"query": "event:auth.otp.verify", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# ─── Lens: Rate limit violations over time ───────────────────────────────────
log "Creating Lens: Rate limit violations"
create_lens "sentinel-rate-limit-hits" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Sentinel — Rate Limit Violations",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["time_col","type_col","count_col"],
              "columns": {
                "time_col":  {"label":"Time","dataType":"date","operationType":"date_histogram","sourceField":"@timestamp","isBucketed":true,"params":{"interval":"auto","dropPartials":false,"includeEmptyRows":true}},
                "type_col":  {"label":"Limit Type","dataType":"string","operationType":"terms","sourceField":"meta.limit_type","isBucketed":true,"params":{"size":5,"orderBy":{"type":"column","columnId":"count_col"},"orderDirection":"desc","parentFormat":{"id":"terms"},"include":[],"exclude":[]}},
                "count_col": {"label":"Violations","dataType":"number","operationType":"count","isBucketed":false}
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "area_stacked",
        "layers": [{"layerId":"main","seriesType":"area_stacked","xAccessor":"time_col","accessors":["count_col"],"splitAccessor":"type_col","layerType":"data"}],
        "legend": {"isVisible":true,"position":"right"},
        "fittingFunction": "None"
      },
      "query": {"query": "event:security.rate_limit.hit", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# ─── Lens: Auth events by type (pie) ─────────────────────────────────────────
log "Creating Lens: Auth events breakdown"
create_lens "sentinel-auth-events" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Sentinel — Auth Events by Type",
    "visualizationType": "lnsPie",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["event_col","count_col"],
              "columns": {
                "event_col": {"label":"Event","dataType":"string","operationType":"terms","sourceField":"event","isBucketed":true,"params":{"size":15,"orderBy":{"type":"column","columnId":"count_col"},"orderDirection":"desc","parentFormat":{"id":"terms"},"include":[],"exclude":[]}},
                "count_col": {"label":"Count","dataType":"number","operationType":"count","isBucketed":false}
              }
            }
          }
        }
      },
      "visualization": {
        "shape":"pie",
        "layers":[{"layerId":"main","primaryGroups":["event_col"],"metrics":["count_col"],"layerType":"data","numberDisplay":"percent","categoryDisplay":"default","legendDisplay":"default","nestedLegend":false}],
        "legend":{"isVisible":true,"position":"right"}
      },
      "query": {"query": "log_type:audit", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# ─── Lens: Login failure heatmap by IP ───────────────────────────────────────
log "Creating Lens: Login failures by IP"
create_lens "sentinel-login-failures" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Sentinel — Login Failures by IP",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["ip_col","count_col"],
              "columns": {
                "ip_col":    {"label":"IP","dataType":"string","operationType":"terms","sourceField":"context.ip","isBucketed":true,"params":{"size":20,"orderBy":{"type":"column","columnId":"count_col"},"orderDirection":"desc","parentFormat":{"id":"terms"},"include":[],"exclude":[]}},
                "count_col": {"label":"Failures","dataType":"number","operationType":"count","isBucketed":false}
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "bar_horizontal",
        "layers":[{"layerId":"main","seriesType":"bar_horizontal","xAccessor":"count_col","accessors":["ip_col"],"layerType":"data"}],
        "legend":{"isVisible":false}
      },
      "query": {"query": "event:auth.otp.verify AND result:failure", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# ─── Lens: Token refresh patterns ────────────────────────────────────────────
log "Creating Lens: Token refresh patterns"
create_lens "sentinel-token-refresh" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Sentinel — Token Refreshes Over Time",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["time_col","count_col"],
              "columns": {
                "time_col":  {"label":"Time","dataType":"date","operationType":"date_histogram","sourceField":"@timestamp","isBucketed":true,"params":{"interval":"auto","dropPartials":false,"includeEmptyRows":true}},
                "count_col": {"label":"Refreshes","dataType":"number","operationType":"count","isBucketed":false}
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "line",
        "layers":[{"layerId":"main","seriesType":"line","xAccessor":"time_col","accessors":["count_col"],"layerType":"data"}],
        "legend":{"isVisible":false},
        "fittingFunction":"None"
      },
      "query": {"query": "event:auth.token.refresh", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# ─── Lens: Security events timeline ──────────────────────────────────────────
log "Creating Lens: Security events timeline"
create_lens "sentinel-security-timeline" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Sentinel — Security Events Timeline",
    "visualizationType": "lnsXY",
    "state": {
      "datasourceStates": {
        "formBased": {
          "layers": {
            "main": {
              "indexPatternId": "tafakoritech-logs",
              "columnOrder": ["time_col","event_col","count_col"],
              "columns": {
                "time_col":  {"label":"Time","dataType":"date","operationType":"date_histogram","sourceField":"@timestamp","isBucketed":true,"params":{"interval":"auto","dropPartials":false,"includeEmptyRows":true}},
                "event_col": {"label":"Event","dataType":"string","operationType":"terms","sourceField":"event","isBucketed":true,"params":{"size":10,"orderBy":{"type":"column","columnId":"count_col"},"orderDirection":"desc","parentFormat":{"id":"terms"},"include":[],"exclude":[]}},
                "count_col": {"label":"Count","dataType":"number","operationType":"count","isBucketed":false}
              }
            }
          }
        }
      },
      "visualization": {
        "preferredSeriesType": "bar_stacked",
        "layers":[{"layerId":"main","seriesType":"bar_stacked","xAccessor":"time_col","accessors":["count_col"],"splitAccessor":"event_col","layerType":"data"}],
        "legend":{"isVisible":true,"position":"right"},
        "fittingFunction":"None"
      },
      "query": {"query": "log_type:audit AND service:sentinel", "language": "kuery"},
      "filters": []
    },
    "references": [{"type":"index-pattern","id":"tafakoritech-logs","name":"indexpattern-datasource-layer-main"}]
  }
}
EOF
)"

# ─── Dashboard ────────────────────────────────────────────────────────────────
log "Creating Sentinel dashboard"
kb_post "/api/saved_objects/dashboard/sentinel-auth?overwrite=true" "$(cat <<'EOF'
{
  "attributes": {
    "title": "Sentinel — Authentication & Security",
    "description": "OTP verification, login failures, rate limiting, token refresh, and security audit events. Filter by trace_id, user_id, actor.user_id, context.ip, or event.",
    "panelsJSON": "[{\"panelIndex\":\"1\",\"gridData\":{\"x\":0,\"y\":0,\"w\":24,\"h\":14,\"i\":\"1\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_1\"},{\"panelIndex\":\"2\",\"gridData\":{\"x\":24,\"y\":0,\"w\":24,\"h\":14,\"i\":\"2\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_2\"},{\"panelIndex\":\"3\",\"gridData\":{\"x\":0,\"y\":14,\"w\":16,\"h\":14,\"i\":\"3\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_3\"},{\"panelIndex\":\"4\",\"gridData\":{\"x\":16,\"y\":14,\"w\":32,\"h\":14,\"i\":\"4\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_4\"},{\"panelIndex\":\"5\",\"gridData\":{\"x\":0,\"y\":28,\"w\":24,\"h\":12,\"i\":\"5\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_5\"},{\"panelIndex\":\"6\",\"gridData\":{\"x\":24,\"y\":28,\"w\":24,\"h\":12,\"i\":\"6\"},\"version\":\"8.13.4\",\"type\":\"lens\",\"embeddableConfig\":{\"enhancements\":{}},\"panelRefName\":\"panel_6\"}]",
    "optionsJSON": "{\"useMargins\":true,\"syncColors\":false,\"hidePanelTitles\":false}",
    "timeRestore": false,
    "kibanaSavedObjectMeta": {
      "searchSourceJSON": "{\"query\":{\"query\":\"service:sentinel\",\"language\":\"kuery\"},\"filter\":[]}"
    }
  },
  "references": [
    {"name":"panel_1","type":"lens","id":"sentinel-otp-verify-rate"},
    {"name":"panel_2","type":"lens","id":"sentinel-rate-limit-hits"},
    {"name":"panel_3","type":"lens","id":"sentinel-auth-events"},
    {"name":"panel_4","type":"lens","id":"sentinel-login-failures"},
    {"name":"panel_5","type":"lens","id":"sentinel-token-refresh"},
    {"name":"panel_6","type":"lens","id":"sentinel-security-timeline"}
  ]
}
EOF
)"

echo ""
log "✓ Sentinel setup complete"
echo ""
echo "  Dashboard: http://${KB_HOST}:${KB_PORT}/app/dashboards#/view/sentinel-auth"
echo "  Discover:  http://${KB_HOST}:${KB_PORT}/app/discover"
echo "  Filter:    log_type:audit AND service:sentinel"
echo ""
warn "Remember to re-apply the index template if new indices were already created:"
warn "  DELETE tafakoritech-logs-* then redeploy, OR apply template with 'reindex'."
