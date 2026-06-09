#!/usr/bin/env bash
# Compare fresh JMH JSON results against a baseline.
#
# Usage:
#   tools/compare-jmh.sh <baseline.json> <fresh.json>
#
# Exit codes:
#   0 — no regression detected (or baseline absent → first run)
#   1 — at least one benchmark exceeded a regression threshold
#   2 — usage error or a required tool is missing
#
# On regression, writes target/jmh-regression.md with a markdown table
# suitable for filing as a GitHub issue body (see nightly workflow).
#
# Thresholds (spec §2 JMH baseline mechanism):
#   avgt mean / p50-ish : +25%
#   sample p99 (only when scorePercentiles."99.0" present in both): +35%
#
# NOTE: p99 comparison requires JMH sample mode (-bm sample).
# Our primary benches use avgt mode, which provides score (mean) and
# scoreError only — percentile comparison is silently skipped for those.

set -euo pipefail

# ---------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------

if [ "$#" -ne 2 ]; then
    echo "Usage: $(basename "$0") <baseline.json> <fresh.json>" >&2
    exit 2
fi

BASELINE="$1"
FRESH="$2"

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------

if ! command -v jq >/dev/null 2>&1; then
    echo "ERROR: 'jq' is required. Install via 'brew install jq' or 'apt-get install jq'." >&2
    exit 2
fi

# A missing baseline is treated as a clean first run (no data to compare
# against). The nightly workflow seeds the baseline on its first successful
# run via update-perf-baseline.yml.
if [ ! -f "$BASELINE" ]; then
    echo "WARN: baseline file '$BASELINE' missing — treating as no-regression (first run)." >&2
    exit 0
fi

if [ ! -f "$FRESH" ]; then
    echo "ERROR: fresh JMH JSON '$FRESH' missing." >&2
    exit 2
fi

# An empty baseline array means no measurements have been committed yet.
# Exit cleanly so the nightly pipeline can proceed to update the baseline.
BASELINE_LEN=$(jq 'length' "$BASELINE")
if [ "$BASELINE_LEN" -eq 0 ]; then
    echo "INFO: baseline is empty — no comparisons to make (first run)." >&2
    exit 0
fi

# ---------------------------------------------------------------------------
# Thresholds
# ---------------------------------------------------------------------------

# Regression is triggered when the fresh value exceeds the baseline by more
# than these percentages (spec §2).
P50_THRESHOLD_PCT=25   # applied to avgt mean (p50-ish) and sample p50
P99_THRESHOLD_PCT=35   # applied to sample p99, when present

# ---------------------------------------------------------------------------
# Build a lookup map from the baseline keyed by "benchmark|params_json".
# jq normalises the params object to a deterministic key string so that
# param orderings do not produce false misses.
# ---------------------------------------------------------------------------

BASELINE_MAP=$(jq '
    reduce .[] as $e (
        {};
        . + {
            ( $e.benchmark
              + "|"
              + ( $e.params // {} | to_entries | sort_by(.key) | map(.key + "=" + (.value | tostring)) | join(",") )
            ): $e
        }
    )
' "$BASELINE")

# ---------------------------------------------------------------------------
# Iterate fresh results, compare, collect regressions
# ---------------------------------------------------------------------------

# regression_rows accumulates tab-separated rows for the markdown report.
regression_rows=()
has_regression=0

while IFS= read -r entry; do
    bench=$(echo "$entry" | jq -r '.benchmark')
    params_key=$(echo "$entry" | jq -r '.params // {} | to_entries | sort_by(.key) | map(.key + "=" + (.value | tostring)) | join(",")')
    lookup_key="${bench}|${params_key}"

    # If the fresh bench has no baseline counterpart, skip — we never flag
    # new benchmarks as regressions; the operator should update the baseline.
    base_entry=$(echo "$BASELINE_MAP" | jq -r --arg k "$lookup_key" '.[$k] // empty')
    if [ -z "$base_entry" ]; then
        echo "INFO: no baseline for '$lookup_key' — skipping." >&2
        continue
    fi

    base_score=$(echo "$base_entry"  | jq -r '.primaryMetric.score')
    fresh_score=$(echo "$entry"       | jq -r '.primaryMetric.score')
    score_unit=$(echo "$entry"        | jq -r '.primaryMetric.scoreUnit')

    # Guard against zero or negative baseline scores (should not occur for
    # timing benchmarks, but protects against division by zero).
    if [ "$(echo "$base_score <= 0" | awk '{print ($1 <= 0) ? "1" : "0"}')" = "1" ]; then
        echo "WARN: baseline score for '$bench' is <= 0 — skipping mean comparison." >&2
    else
        # Compute percentage change using awk (bc handles integers only).
        delta_pct=$(awk -v f="$fresh_score" -v b="$base_score" \
            'BEGIN { printf "%.2f", (f - b) / b * 100 }')

        # Determine human-readable display params (empty string if no params).
        display_params="${params_key}"

        if awk -v d="$delta_pct" -v t="$P50_THRESHOLD_PCT" 'BEGIN { exit (d > t) ? 0 : 1 }'; then
            regression_rows+=("${bench}\t${display_params}\t${base_score}\t${fresh_score}\t${delta_pct}%\t${score_unit}\tREGRESSION (mean >+${P50_THRESHOLD_PCT}%)")
            has_regression=1
            echo "REGRESSION: $bench ($display_params) mean +${delta_pct}% (threshold +${P50_THRESHOLD_PCT}%)" >&2
        else
            echo "OK: $bench ($display_params) mean +${delta_pct}%" >&2
        fi
    fi

    # ---------------------------------------------------------------------------
    # p99 comparison — only when both baseline and fresh expose scorePercentiles.
    # Requires sample mode (-bm sample) in the JMH invocation; silently skipped
    # for avgt mode benches (future work: add sample-mode benches for tail-latency).
    # ---------------------------------------------------------------------------
    base_p99=$(echo "$base_entry" | jq -r '.primaryMetric.scorePercentiles["99.0"] // empty')
    fresh_p99=$(echo "$entry"     | jq -r '.primaryMetric.scorePercentiles["99.0"] // empty')

    if [ -n "$base_p99" ] && [ -n "$fresh_p99" ]; then
        if [ "$(awk -v b="$base_p99" 'BEGIN { print (b <= 0) ? "1" : "0" }')" = "1" ]; then
            echo "WARN: baseline p99 for '$bench' is <= 0 — skipping p99 comparison." >&2
        else
            delta_p99=$(awk -v f="$fresh_p99" -v b="$base_p99" \
                'BEGIN { printf "%.2f", (f - b) / b * 100 }')

            if awk -v d="$delta_p99" -v t="$P99_THRESHOLD_PCT" 'BEGIN { exit (d > t) ? 0 : 1 }'; then
                regression_rows+=("${bench}\t${display_params:-}\t${base_p99}\t${fresh_p99}\t${delta_p99}%\t${score_unit} (p99)\tREGRESSION (p99 >+${P99_THRESHOLD_PCT}%)")
                has_regression=1
                echo "REGRESSION: $bench ($display_params) p99 +${delta_p99}% (threshold +${P99_THRESHOLD_PCT}%)" >&2
            else
                echo "OK: $bench ($display_params) p99 +${delta_p99}%" >&2
            fi
        fi
    fi

done < <(jq -c '.[]' "$FRESH")

# ---------------------------------------------------------------------------
# Write regression report
# ---------------------------------------------------------------------------

if [ "$has_regression" -eq 1 ]; then
    mkdir -p target

    REPORT="target/jmh-regression.md"
    {
        echo "# JMH Performance Regression Report"
        echo ""
        echo "**Generated:** $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
        echo "**Baseline:** \`$BASELINE\`"
        echo "**Fresh:**    \`$FRESH\`"
        echo "**Thresholds:** mean/p50 > +${P50_THRESHOLD_PCT}%, p99 > +${P99_THRESHOLD_PCT}%"
        echo ""
        echo "## Regressions"
        echo ""
        echo "| Benchmark | Params | Baseline | Fresh | Delta | Unit | Status |"
        echo "|-----------|--------|----------|-------|-------|------|--------|"
        for row in "${regression_rows[@]}"; do
            # Replace tab separators with pipe-table separators.
            echo "| $(echo -e "$row" | sed 's/\t/ | /g') |"
        done
        echo ""
        echo "> Investigate the benchmarks listed above and update"
        echo "> \`.github/perf-baselines/jmh.json\` via the"
        echo "> \`update-perf-baseline.yml\` workflow once the regression is"
        echo "> accepted or resolved."
    } > "$REPORT"

    echo "Regression report written to: $REPORT" >&2
    exit 1
fi

echo "INFO: No regressions detected." >&2
exit 0
