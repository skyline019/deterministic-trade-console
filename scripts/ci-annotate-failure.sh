#!/usr/bin/env sh
set -eu
# Turn a failed CI run into GitHub *annotations*, so the reason is visible on the PR and readable
# through the API even when the raw job log is not downloadable from a restricted network.
#
# Sources, in order:
#   1. JUnit/Surefire/Failsafe text reports (test name + first lines of the stack trace)
#   2. an explicit log file (e.g. the maintenance drill output)
#
# Usage: ./scripts/ci-annotate-failure.sh [log-file ...]
# Annotations are emitted with `::error::`, which needs %25/%0D/%0A escaping for multi-line text.

esc() {
    # %, CR and LF must be percent-encoded for the workflow command protocol.
    printf '%s' "$1" | sed -e 's/%/%25/g' -e 's/\r//g' | awk 'BEGIN{ORS="%0A"} {print}' | sed -e 's/%0A$//'
}

annotate() {
    title=$1
    body=$2
    [ -n "$body" ] || return 0
    printf '::error title=%s::%s\n' "$title" "$(esc "$body")"
}

printf '%s\n' '--- collecting failure details ---'

# 1) Test reports. A failing report looks like:
#      Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.05 s <<< FAILURE!
#      method_name  Time elapsed: 0.01 s  <<< FAILURE!
#      java.lang.AssertionError: expected ...
#          at ...
seen=' '
for report in target/surefire-reports/*.txt target/failsafe-reports/*.txt target/*-reports/*.txt; do
    [ -f "$report" ] || continue
    # The globs overlap by design (profiles vary); annotate each report once.
    case "$seen" in *" $report "*) continue ;; esac
    seen="$seen$report "
    grep -qE '(Failures: [1-9]|Errors: [1-9])' "$report" || continue
    details=$(awk '
        /<<< (FAILURE|ERROR)!$/ { keep = 1; count = 0 }
        keep {
            print
            count++
            if (count >= 12) { keep = 0; print "..." }
            next
        }
    ' "$report" | head -60)
    annotate "$(basename "$report")" "$details"
done

# 2) Explicit logs. The interesting part is usually the *head* of the first exception, not the tail
#    of a 40-frame stack trace, so emit both: the first exception lines, then the last few lines.
for log in "$@"; do
    [ -f "$log" ] || continue
    head_of_failure=$(grep -nE '(Exception|Error:|SQL State|Message *:|FAILED|ERROR)' "$log" \
        | head -8 | cut -d: -f2-)
    annotate "$(basename "$log") head" "$head_of_failure"
    annotate "$(basename "$log") tail" "$(tail -15 "$log")"
done
