#!/usr/bin/env bash
#
# Smoke-test the assembled rule editor bundle (PLAN-rule-editor phase 4).
#
# ⛔ WHY THIS EXISTS. A zip can assemble perfectly green and still be unrunnable — an
# empty lib/, an assembly declared before the jar plugin, an unresolvable ${...} in
# the sidecar .conf, a launcher that is not the launcher. Nothing in `mvn verify`
# starts the bundle. This does, and then it does what the SPA does on day one:
#
#   * starts a fake forge (src/test/smoke-forge.py: the two API endpoints the login
#     probe reads + the fixture repository over git smart HTTP via `git http-backend`),
#   * starts the bundle from an unrelated CWD, bound to that forge through
#     config/application.yaml (so a service answering on the port read that file),
#   * logs in (CSRF token, then the login POST) and expects EDIT mode — which means the
#     identity probe, the repository probe AND the receive-pack advertisement all
#     passed, and the workspace was cloned over HTTP with the token,
#   * reads the explorer, creates a branch (the default branch is read-only), opens a
#     rule, saves an edit with the optimistic stamp, reads it back, commits and pushes,
#   * and proves the push by reading the commit out of the fixture's bare repository.
#
# Since phase 3 it also analyses the edited rule and runs it against the seeded scenario,
# which proves the engine jars are in lib/ and that a child JVM worker starts from them.
#
# ⚠ WHAT THIS DOES NOT PROVE: the SPA. When the jar was built with -Pbundle-web the
# bundled index.html is asserted to be served at /, nothing more; the SPA's own gate
# is `npm run verify` in cumba-oss-corej-rule-editor-web.
#
# Usage: smoke.sh <path-to-exploded-bundle>

set -euo pipefail

# ⚠ PREFLIGHT. Several assertions below are `grep -q`, and a missing tool exiting 127 is
# indistinguishable from "no match". python3 runs the fake forge and reads the JSON
# answers; git seeds the fixture and is what `git http-backend` is; curl carries the
# cookie jar (the session) and the CSRF header — unlike cumba-oss-corej-rest's smoke, this
# one needs POSTs with cookies, so curl is required, not optional.
for tool in cat curl git grep java ls mkdir mktemp python3 rm seq sleep tail timeout unzip wc; do
    command -v "$tool" >/dev/null 2>&1 \
        || { echo "smoke: required tool not found: $tool" >&2; exit 1; }
done

BUNDLE="${1:?usage: smoke.sh <path-to-exploded-bundle>}"
[ -d "$BUNDLE" ] || { echo "smoke: not a directory: $BUNDLE" >&2; exit 1; }
# Absolute: the run below deliberately happens from an unrelated CWD.
BUNDLE="$(cd "$BUNDLE" && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for f in run.sh run.bat cumba-oss-corej-rule-editor-rest.jar cumba-oss-corej-rule-editor-rest.conf \
         config/application.yaml config/README.md; do
    [ -e "$BUNDLE/$f" ] || { echo "smoke: bundle is missing $f" >&2; exit 1; }
done
[ -x "$BUNDLE/run.sh" ] || { echo "smoke: run.sh is not executable" >&2; exit 1; }

# The application jar must be in lib/, not just its dependencies. An assembly declared
# before maven-jar-plugin silently produces a bundle without it.
ls "$BUNDLE"/lib/cumba-oss-corej-rule-editor-rest-*.jar >/dev/null 2>&1 \
    || { echo "smoke: no cumba-oss-corej-rule-editor-rest-*.jar in $BUNDLE/lib" >&2; exit 1; }

# A crude floor, not a manifest: it catches a dependencySet that resolved the wrong scope
# (or nothing). Boot web + security + springdoc + JGit + the engine (phase 3) is ~90 jars;
# under 75 the graph, not a single artifact, went wrong.
libcount="$(ls "$BUNDLE"/lib/*.jar 2>/dev/null | wc -l)"
[ "$libcount" -ge 75 ] \
    || { echo "smoke: only $libcount jars in $BUNDLE/lib — expected ~90; the dependencySet resolved the wrong scope?" >&2; exit 1; }
ls "$BUNDLE"/lib/cumba-oss-corej-authoring-*.jar "$BUNDLE"/lib/cumba-oss-corej-ruletest-*.jar >/dev/null 2>&1 \
    || { echo "smoke: the engine jars (cumba-oss-corej-authoring, -ruletest) are not in $BUNDLE/lib" >&2; exit 1; }

work="$(mktemp -d)"
pid=""
forge_pid=""
cleanup() {
    for p in "$pid" "$forge_pid"; do
        if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
            kill "$p" 2>/dev/null || true
            for _ in $(seq 1 20); do kill -0 "$p" 2>/dev/null || break; sleep 0.5; done
            kill -9 "$p" 2>/dev/null || true
            wait "$p" 2>/dev/null || true
        fi
    done
    rm -rf "$work"
}
trap cleanup EXIT

# Two free ports, found by probing rather than assumed: CI runners are shared.
free_port() {
    local candidate
    for candidate in $(seq "$1" "$2"); do
        if ! (exec 3<>"/dev/tcp/127.0.0.1/$candidate") 2>/dev/null; then
            echo "$candidate"; return 0
        fi
    done
    return 1
}
port="$(free_port 18770 18800)" || { echo "smoke: no free port in 18770-18800" >&2; exit 1; }
forge_port="$(free_port 18801 18830)" || { echo "smoke: no free port in 18801-18830" >&2; exit 1; }

# ---- the fixture repository: one rule, its docs, one scenario, on main ---------------
TOKEN="smoke-token-$$"
RULE="rules-src/checks/CDISC/CDISC-SM0001.yaml"
seed="$work/seed"
mkdir -p "$seed/rules-src/checks/CDISC" "$seed/rules-src/docs/CDISC" \
         "$seed/src/test/resources/net/cumba/corej/core/ruletestsuites/cdisc/CDISC-SM0001"
cat > "$seed/$RULE" <<'EOF'
Core:
  Id: "CDISC-SM0001"
  Status: "Draft"
  Version: "1"
Description: "Smoke-test rule"
Scope:
  Domains:
    Include:
    - "ALL"
Check:
  expression: >-
    not ds_exists("DM")
Outcome:
  Message: "DM is missing"
EOF
printf 'Core: "CDISC-SM0001"\nCitations: {}\n' > "$seed/rules-src/docs/CDISC/CDISC-SM0001.yaml"
SCENARIO="src/test/resources/net/cumba/corej/core/ruletestsuites/cdisc/CDISC-SM0001/CDISC-SM0001-valid-DM.cdt"
# DM is present, so `not ds_exists("DM")` fires nothing: expect=noViolation passes. The rule's
# Scope is ALL on purpose: a dataset-presence check derives Sensitivity `Study`, which the engine
# only accepts with an unrestricted scope (measured 2026-10-06: a DM-scoped seed was LOAD_ERROR).
cat > "$seed/$SCENARIO" <<'CDT'
#!RuleTest
#test CDISC-SM0001 expect=noViolation domain=DM
#expectViolationCount 0
dataset DM label="Demographics"
col STUDYID type=Char label="Study Identifier"
col USUBJID type=Char label="Unique Subject Identifier"
---
STUDY1 | 01-001
---
CDT
git -C "$seed" init -q -b main
git -C "$seed" -c user.name=Seed -c user.email=seed@example.org add -A
git -C "$seed" -c user.name=Seed -c user.email=seed@example.org commit -q -m "seed"
mkdir -p "$work/forge/acme"
git clone -q --bare "$seed" "$work/forge/acme/rules.git"
# git http-backend refuses receive-pack unless told so (or REMOTE_USER is set); both, to be sure.
git -C "$work/forge/acme/rules.git" config http.receivepack true

forge_log="$work/forge.log"
python3 "$HERE/smoke-forge.py" "$work/forge" "$forge_port" "$TOKEN" > "$forge_log" 2>&1 &
forge_pid=$!
for _ in $(seq 1 50); do
    if curl -fsS -m 2 -H "Authorization: token $TOKEN" "http://127.0.0.1:$forge_port/api/v1/user" >/dev/null 2>&1; then
        break
    fi
    kill -0 "$forge_pid" 2>/dev/null || { echo "smoke: the fake forge exited:" >&2; cat "$forge_log" >&2; exit 1; }
    sleep 0.2
done
curl -fsS -m 2 -H "Authorization: token $TOKEN" "http://127.0.0.1:$forge_port/api/v1/user" >/dev/null \
    || { echo "smoke: the fake forge does not answer on $forge_port" >&2; cat "$forge_log" >&2; exit 1; }

# ---- the service, bound to the fixture through EXTERNAL configuration ----------------
# The packaged application.yaml names the real Gitea; if the service clones the fixture,
# Spring read this file (and the .conf's additional-location line resolved).
mkdir -p "$work/config"
cat > "$work/config/application.yaml" <<EOF
server:
  port: $port
  servlet:
    session:
      cookie:
        secure: false
editor:
  repository:
    url: http://127.0.0.1:$forge_port/acme/rules.git
  forge:
    type: gitea
    api-url: http://127.0.0.1:$forge_port/api/v1
  workspace-root: $work/workspaces
EOF

log="$work/service.log"
# ⛔ THE `exec` IS LOAD-BEARING: run.sh ends in `exec java`, so with a plain subshell the
# JVM is a GRANDCHILD of $! and the trap cannot reach it. `exec` collapses the subshell
# into run.sh and run.sh into java, so $! is the JVM itself.
( cd "$work" && exec env COREJ_CONFIG_DIR="$work/config" "$BUNDLE/run.sh" ) > "$log" 2>&1 &
pid=$!

fail() {
    echo "smoke: $1" >&2
    if grep -qE 'NoClassDefFoundError|ClassNotFoundException' "$log" 2>/dev/null; then
        echo "smoke: cause — the bundle's classpath is incomplete:" >&2
        grep -oE '(NoClassDefFoundError|ClassNotFoundException): [^ ]+' "$log" | sort -u >&2
    elif grep -q '^bootstrap: ' "$log" 2>/dev/null; then
        echo "smoke: cause — the launcher rejected its configuration:" >&2
        grep '^bootstrap: ' "$log" >&2
    fi
    echo "--- service log (tail) ---" >&2
    tail -40 "$log" >&2 2>/dev/null || echo "(no log)" >&2
    echo "--- forge log (tail) ---" >&2
    tail -20 "$forge_log" >&2 2>/dev/null || true
    exit 1
}

base="http://127.0.0.1:$port"
jar="$work/cookies"
# JSON field access: `jq`-free, python3 is required anyway. `json_get <expr>` reads stdin.
json_get() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))' "$1"; }

body=""
for _ in $(seq 1 120); do
    kill -0 "$pid" 2>/dev/null || fail "the service exited before it answered"
    if body="$(curl -fsS -m 3 "$base/api/info" 2>/dev/null)" && [ -n "$body" ]; then
        break
    fi
    body=""
    sleep 1
done
[ -n "$body" ] || fail "no response from /api/info on port $port within 120s"
grep -q '"service":"corej-rule-editor-rest"' <<<"$body" \
    || fail "/api/info did not identify the service: $body"
grep -q '"name":"rules"' <<<"$body" \
    || fail "/api/info does not name the fixture repository — the external config was not read: $body"

# ---- login: CSRF token, then the POST; EDIT mode proves all three probes ----------------
csrf() { curl -fsS -m 5 -c "$jar" -b "$jar" "$base/api/session/csrf" | json_get "d['token']"; }
token="$(csrf)"
[ -n "$token" ] || fail "no CSRF token"
login="$(curl -sS -m 60 -c "$jar" -b "$jar" -H "X-CSRF-TOKEN: $token" -H 'Content-Type: application/json' \
    -d "{\"login\":\"smoke\",\"token\":\"$TOKEN\"}" "$base/api/session/login")" || fail "login request failed"
grep -q '"mode":"EDIT"' <<<"$login" \
    || fail "login did not yield EDIT mode (identity / repository / receive-pack probe, or the clone): $login"
# the session was rotated on login: re-fetch the token
token="$(csrf)"

# ---- explorer, branch, open, save, read back, commit, push ------------------------------
curl -fsS -m 10 -b "$jar" "$base/api/explorer" | grep -q '"id":"CDISC-SM0001"' \
    || fail "the explorer does not list the seeded rule"

status="$(curl -fsS -m 10 -b "$jar" "$base/api/status")"
branch="$(json_get "d['branch']" <<<"$status")"
head="$(json_get "d['head']" <<<"$status")"
[ "$branch" = "main" ] || fail "expected to start on main, got $branch"

created="$(curl -sS -m 30 -b "$jar" -H "X-CSRF-TOKEN: $token" -H 'Content-Type: application/json' \
    -d "{\"name\":\"smoke/edit\",\"branch\":\"$branch\",\"head\":\"$head\"}" "$base/api/branches")"
[ "$(json_get "d.get('current')" <<<"$created")" = "smoke/edit" ] || fail "branch creation failed: $created"
branch="smoke/edit"
head="$(json_get "d['head']" <<<"$created")"

file="$(curl -fsS -m 10 -b "$jar" --get --data-urlencode "path=$RULE" "$base/api/files/content")"
blob="$(json_get "d['blob']" <<<"$file")"
grep -q 'Smoke-test rule' <<<"$file" || fail "the rule did not open: $file"

new_content="$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps(d["content"].replace("Smoke-test rule", "Smoke-test rule, edited")))' <<<"$file")"
written="$(curl -sS -m 30 -b "$jar" -X PUT -H "X-CSRF-TOKEN: $token" -H 'Content-Type: application/json' \
    -d "{\"path\":\"$RULE\",\"content\":$new_content,\"blob\":\"$blob\",\"branch\":\"$branch\",\"head\":\"$head\"}" \
    "$base/api/files/content")"
grep -q '"blob":"' <<<"$written" || fail "the save was refused: $written"

curl -fsS -m 10 -b "$jar" --get --data-urlencode "path=$RULE" "$base/api/files/content" | grep -q 'rule, edited' \
    || fail "the saved content did not read back"
curl -fsS -m 10 -b "$jar" "$base/api/status" | grep -q "\"path\":\"$RULE\"" \
    || fail "/api/status does not list the saved file as uncommitted"

# ---- phase 3: analyse the edited rule, run it against the seeded scenario ---------------
# The engine is on the bundle's classpath (cumba-oss-corej-authoring, -ruletest, …): the analysis
# must load the rule, and the run must start a CHILD JVM worker from lib/* (the worker's class
# path is derived from the launcher's bootstrap.dir) and bring the corpus gate's verdict back.
# No metadata store is configured here: the analysis says so, the run needs none.
analysed="$(curl -sS -m 60 -b "$jar" -H "X-CSRF-TOKEN: $token" -H 'Content-Type: application/json' \
    -d "{\"path\":\"$RULE\",\"content\":$new_content}" "$base/api/analyse")" || fail "analyse request failed"
[ "$(json_get "d['analysis']['loaded']" <<<"$analysed")" = "True" ] \
    || fail "the analysis did not load the rule: $analysed"
[ "$(json_get "d['analysis']['storeAvailable']" <<<"$analysed")" = "False" ] \
    || fail "no store was configured, yet the analysis claims one: $analysed"
grep -q '"outputVariables"' <<<"$analysed" || fail "the analysis carries no derived fields: $analysed"

scenario="$(curl -fsS -m 10 -b "$jar" --get --data-urlencode "path=$SCENARIO" "$base/api/files/content")"
scenario_content="$(python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["content"]))' <<<"$scenario")"
run="$(curl -sS -m 180 -b "$jar" -H "X-CSRF-TOKEN: $token" -H 'Content-Type: application/json' \
    -d "{\"path\":\"$RULE\",\"content\":$new_content,\"scenarioPath\":\"$SCENARIO\",\"scenarioContent\":$scenario_content}" \
    "$base/api/run")" || fail "run request failed"
[ "$(json_get "d['report']['outcome']" <<<"$run")" = "PASS" ] \
    || fail "the run did not pass (a worker could not start, or the verdict differs): $run"
[ "$(json_get "d['report']['worker']" <<<"$run")" = "child" ] \
    || fail "the run did not go through a child JVM worker: $run"
echo "smoke: analysed CDISC-SM0001 (loaded, no store) and ran it in a child worker -> PASS"

committed="$(curl -sS -m 30 -b "$jar" -H "X-CSRF-TOKEN: $token" -H 'Content-Type: application/json' \
    -d "{\"message\":\"smoke: edit CDISC-SM0001\",\"branch\":\"$branch\",\"head\":\"$head\"}" "$base/api/commit")"
head="$(json_get "d['head']" <<<"$committed")" || fail "the commit was refused: $committed"
pushed="$(curl -sS -m 60 -b "$jar" -H "X-CSRF-TOKEN: $token" -H 'Content-Type: application/json' \
    -d "{\"branch\":\"$branch\",\"head\":\"$head\"}" "$base/api/push")"
grep -q '"status":"OK"' <<<"$pushed" || fail "the push was refused: $pushed"

# the proof is in the fixture's bare repository, not in the service's answer
git -C "$work/forge/acme/rules.git" log -1 --format=%s smoke/edit 2>/dev/null | grep -q 'smoke: edit CDISC-SM0001' \
    || fail "the pushed commit is not on the fixture's smoke/edit branch"
git -C "$work/forge/acme/rules.git" show "smoke/edit:$RULE" | grep -q 'rule, edited' \
    || fail "the pushed tree does not hold the edit"
git -C "$work/forge/acme/rules.git" show "main:$RULE" | grep -q 'rule, edited' \
    && fail "the edit reached main — the default branch must stay untouched"

# ---- the bundled SPA, when the jar carries one (-Pbundle-web) ---------------------------
appjar="$(ls "$BUNDLE"/lib/cumba-oss-corej-rule-editor-rest-*.jar | head -1)"
# ⚠ Not `grep -q`: it exits on the first match, unzip then dies of SIGPIPE, and under
# `pipefail` the pipeline reports failure — a bundled SPA read as "API-only" (measured).
if unzip -l "$appjar" 2>/dev/null | grep 'static/index.html' >/dev/null; then
    curl -fsS -m 5 "$base/" | grep -q 'id="root"' || fail "the bundled SPA is not served at /"
    echo "smoke: bundled SPA served at /"
else
    echo "smoke: API-only jar (no static/index.html bundled)"
fi

echo "smoke: OK ($libcount jars in lib/; login -> EDIT, branch, save, analyse, run, commit, push proved in the fixture)"
