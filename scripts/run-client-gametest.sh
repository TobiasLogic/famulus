#!/usr/bin/env bash
set -uo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

log="$project_dir/.cache/clientgametest.log"
run_dir="$project_dir/fabric/build/run/clientGameTest"
archive="$project_dir/docs/acceptance/$(date -u +%Y-%m-%d)"

mkdir -p "$project_dir/.cache"

if [[ "${1:-}" != '--classify-only' ]]; then
    echo "Running the client game test. A Minecraft window opens and is driven automatically."
    GRADLE_USER_HOME="$project_dir/.cache/gradle" ./gradlew :fabric:runClientGameTest --console=plain > "$log" 2>&1
    gradle_status=$?
else
    gradle_status="${GAMETEST_STATUS:-0}"
fi

if [[ ! -f "$log" ]]; then
    echo 'FAIL: no run log was produced.' >&2
    exit 1
fi

failures=0
require_pattern() {
    if grep -qE "$1" "$log"; then
        echo "  ok   $2"
    else
        echo "  MISS $2"
        failures=$((failures + 1))
    fi
}

require_line() {
    if grep -qF "$1" "$log"; then
        echo "  ok   $2"
    else
        echo "  MISS $2"
        failures=$((failures + 1))
    fi
}

echo
echo 'Acceptance evidence:'
require_line 'SUCCESS | 32/32 | attempts 1 | Inventory target reached' \
    'gathered to an observed inventory total of 32 in one attempt'
require_line 'SUCCESS | 32/32 | attempts 0 | Inventory target already satisfied' \
    'an already satisfied request completed without mining'
require_line 'CANCELLED | 32/96 | attempts 1 | Stopped by user' \
    '/famulus stop cancelled an in-progress task'
require_line '[agent] plan started: queued gather (2 tasks)' \
    'a two-task plan started'
require_pattern '\[agent\] running (gather|mine .* for) 8 minecraft:dirt' \
    'the agent advanced to its second task unprompted'
require_line 'plan PLAN_COMPLETE' \
    'the plan ran to completion'

for shot in gather-running gather-completed inventory-32-oak-logs already-satisfied stopped plan-first-task plan-complete screen-agent screen-chat screen-build screen-settings deposit-done shulker-deposit craft-done mine-done place-done interact-done; do
    if compgen -G "$run_dir/screenshots/*${shot}.png" > /dev/null; then
        echo "  ok   screenshot ${shot}"
    else
        echo "  MISS screenshot ${shot}"
        failures=$((failures + 1))
    fi
done

# The policy phase only runs when a key is present, so only require its evidence then.
if [[ -n "${OPENROUTER_API_KEY:-}" ]]; then
    require_line '[agent] policy chose' \
        'the policy layer was consulted for a task that could not succeed'
fi

if grep -q 'AssertionError' "$log"; then
    echo '  FAIL an in-game assertion failed:'
    grep -m 5 -A 2 'AssertionError' "$log" | sed 's/^/       /'
    failures=$((failures + 1))
else
    echo '  ok   no in-game assertion failed'
fi

# Baritone 1.19.0 leaves a non-daemon thread pool running after the client stops,
# so the JVM cannot exit and Minecraft's shutdown watchdog kills it. That teardown
# defect is upstream and is accepted only when it is exactly that signature.
teardown_defect=0
if [[ "$gradle_status" -ne 0 ]]; then
    crash="$(ls -1t "$run_dir"/crash-reports/crash-*-client.txt 2>/dev/null | head -1)"
    if [[ -n "$crash" ]] && grep -q 'Description: Client shutdown from post-main' "$crash" \
        && grep -q 'at knot//baritone\.' "$crash"; then
        stranded="$(awk -F'"' '/^"[^"]+" (daemon )?prio=/ && $0 !~ / daemon prio=/ { print $2 }' "$crash" \
            | grep -vE '^(DestroyJavaVM|pool-[0-9]+-thread-[0-9]+)$' || true)"
        if [[ -z "$stranded" ]]; then
            teardown_defect=1
        else
            echo '  FAIL non-daemon threads outside Baritone survived shutdown:'
            printf '       %s\n' $stranded
            failures=$((failures + 1))
        fi
    else
        echo "  FAIL the client exited non-zero for a reason other than the known Baritone teardown defect (gradle status $gradle_status)."
        failures=$((failures + 1))
    fi
fi

if [[ -d "$run_dir/screenshots" ]]; then
    mkdir -p "$archive/screenshots"
    cp "$run_dir"/screenshots/*.png "$archive/screenshots/" 2>/dev/null
    grep -E '\[Famulus\]|\(Famulus\)|\[Baritone\]|Successfully filled|Gave 1|No items were found|Teleported|Stopping!|Description:' \
        "$log" > "$archive/transcript.txt"
    crash="$(ls -1t "$run_dir"/crash-reports/crash-*-client.txt 2>/dev/null | head -1)"
    [[ -n "$crash" ]] && cp "$crash" "$archive/shutdown-crash-report.txt"
    echo
    echo "Evidence archived to ${archive#"$project_dir"/}"
fi

echo
if [[ "$failures" -ne 0 ]]; then
    echo "FAIL: $failures acceptance check(s) did not hold."
    exit 1
fi
if [[ "$teardown_defect" -eq 1 ]]; then
    echo 'PASS, with the known Baritone shutdown defect.'
    echo 'Every gather assertion held. Baritone did not release its non-daemon thread'
    echo 'pool, so the client was killed by the shutdown watchdog after the test ended.'
    echo 'See BUGS.md. The gradle task itself stays red for this reason.'
    exit 0
fi
echo 'PASS.'
exit 0
