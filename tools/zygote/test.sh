#!/usr/bin/env bash
# LSPFRIDA zygote gate — host test suite.
#
#   bash tools/zygote/test.sh
#
# Prints PASS/FAIL lines and exits non-zero on any failure.  Evidence produced:
#   - component tests for maps/ELF/payload/state/wire modules;
#   - END-TO-END mock-zygote scenario 1: real injector patch of a real process,
#     forked child runs the real gate payload, hello/ack over abstract socket,
#     child SIGSTOP, daemon reverts child patches, SIGCONT, env markers and
#     original-function assertions, daemon SIGTERM reverts the zygote patch;
#   - END-TO-END scenario 2: no daemon -> fail-open (no SIGSTOP), env markers
#     still written, then `revert` from the persisted state file.
#
# NOTE (sandbox): this host denies O_WRONLY on /proc/<pid>/mem, so the injector
# prints one WARN and uses its documented process_vm_writev fallback.  The E2E
# exercises the same semantics; on Android the primary /proc/pid/mem pwrite
# path is used (README.md "Known limits").
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/out/host
PASS_COUNT=0
FAIL_COUNT=0

pass () { echo "PASS $*"; PASS_COUNT=$((PASS_COUNT + 1)); }
fail () { echo "FAIL $*"; FAIL_COUNT=$((FAIL_COUNT + 1)); }
check_rc () {
  local rc=$1 expected=$2 name=$3
  if [ "$rc" -eq "$expected" ]; then pass "$name (rc=$rc)"; else fail "$name (rc=$rc want $expected)"; fi
}

TMP=$(mktemp -d "/tmp/lspfrifa-e2e.XXXXXX") || exit 1
DAEMON1_PID=""
DAEMON2_PID=""
MOCK1_PID=""
MOCK2_PID=""

cleanup () {
  [ -n "$DAEMON1_PID" ] && kill -KILL "$DAEMON1_PID" 2>/dev/null
  [ -n "$DAEMON2_PID" ] && kill -KILL "$DAEMON2_PID" 2>/dev/null
  [ -n "$MOCK1_PID" ] && kill -KILL "$MOCK1_PID" 2>/dev/null
  [ -n "$MOCK2_PID" ] && kill -KILL "$MOCK2_PID" 2>/dev/null
  [ -n "$MOCK3_PID" ] && kill -KILL "$MOCK3_PID" 2>/dev/null
  [ -n "$DAEMON3_PID" ] && kill -KILL "$DAEMON3_PID" 2>/dev/null
  if [ "${TEST_KEEP:-0}" = "1" ]; then
    echo "kept test dir: $TMP"
  else
    rm -rf "$TMP"
  fi
}
trap cleanup EXIT

wait_for_file () {
  local file=$1 timeout=${2:-10} i
  for ((i = 0; i < timeout * 20; i++)); do
    [ -s "$file" ] && return 0
    sleep 0.05
  done
  return 1
}

wait_for_grep () {
  local file=$1 pattern=$2 timeout=${3:-10} i
  for ((i = 0; i < timeout * 20; i++)); do
    [ -f "$file" ] && grep -q "$pattern" "$file" && return 0
    sleep 0.05
  done
  return 1
}

echo "== build =="
if ! bash "$HERE/build.sh"; then
  echo "FAIL build"
  exit 1
fi
pass "build.sh"

export LD_LIBRARY_PATH="$HERE/tests/mock:${LD_LIBRARY_PATH:-}"

echo
echo "== component tests =="
mkdir -p "$TMP/component-state"
if "$OUT/component_tests" "$HERE/tests/mock" "$OUT/lspfrifa-gate.elf" \
    "$TMP/component-state" >"$TMP/component.log" 2>&1; then
  cat "$TMP/component.log"
  pass "component tests (all)"
else
  cat "$TMP/component.log"
  fail "component tests"
fi

echo
echo "== self-test =="
if "$OUT/lspfrifa-zygote" self-test --state "$TMP/selftest-state" \
    >"$TMP/selftest.log" 2>&1; then
  grep -v '^PASS self-test' "$TMP/selftest.log" || true
  pass "self-test"
else
  cat "$TMP/selftest.log"
  fail "self-test"
fi

echo
echo "== CLI checks =="
"$OUT/lspfrifa-zygote" targets >"$TMP/targets.log" 2>&1
check_rc $? 0 "targets"
"$OUT/lspfrifa-zygote" >/dev/null 2>&1
check_rc $? 2 "usage/no-args"
"$OUT/lspfrifa-zygote" not-a-command >/dev/null 2>&1
check_rc $? 2 "usage/unknown-command"
if grep -rIn -E 'PTRACE_(ATTACH|SEIZE)|ptrace[[:space:]]*\(' "$HERE/src" "$HERE/tests" >/dev/null 2>&1; then
  fail "no-ptrace (PTRACE_ATTACH/ptrace found in sources)"
else
  pass "no-ptrace (SIGSTOP+/proc/pid/mem only)"
fi

# ---------------------------------------------------------------------------
# Scenario 1: inject --serve, real gate run, child revert, daemon revert
# ---------------------------------------------------------------------------
echo
echo "== E2E scenario 1: inject --serve + real gate + revert =="
S1="$TMP/s1"
mkdir -p "$S1/state"

coproc M1 { exec "$OUT/mock_zygote" "$S1" 2>"$S1/mock.err"; }
MOCK1_PID=$M1_PID

if read -t 10 -r READY_LINE <&"${M1[0]}"; then
  MZYG=$(printf '%s\n' "$READY_LINE" | awk '{print $2}')
  pass "mock_zygote ready (pid $MZYG)"
else
  fail "mock_zygote ready"
  MZYG=""
fi

if [ -n "$MZYG" ]; then
  "$OUT/lspfrifa-zygote" inject --pid "$MZYG" --serve \
      --socket lspfrifa-zygote-test \
      --gate "$OUT/lspfrifa-gate.elf" \
      --libc-match libc.so.6 \
      --runtime-match libandroid_runtime.so \
      --selinux-match libselinux.so \
      --payload-match libstagefright.so \
      --state "$S1/state" >"$S1/daemon.log" 2>&1 &
  DAEMON1_PID=$!

  if wait_for_grep "$S1/daemon.log" '"event":"patch"' 10; then
    pass "inject --pid $MZYG (patch event)"
  else
    fail "inject --pid $MZYG"
    cat "$S1/daemon.log" "$S1/mock.err" 2>/dev/null
  fi

  # trigger the mock zygote: fork a child that runs the gate
  printf 'go\n' >&"${M1[1]}"

  if wait_for_file "$S1/child_result.json" 15; then
    pass "child wrote result"
  else
    fail "child wrote result"
  fi
  if wait_for_grep "$S1/daemon.log" '"event":"gate_hit"' 15; then
    pass "daemon emitted gate_hit"
  else
    fail "daemon emitted gate_hit"
  fi

  python3 - "$S1" "$MZYG" <<'PY' >"$S1/assert1.log" 2>&1
import json, sys
s1, mzyg = sys.argv[1], int(sys.argv[2])
failed = 0
def check(name, condition):
    global failed
    print(("PASS " if condition else "FAIL ") + name)
    if not condition:
        failed += 1
try:
    child = json.load(open(s1 + "/child_result.json"))
except Exception as e:
    print("FAIL e2e1/child_result_readable (%s)" % e); sys.exit(1)
log = open(s1 + "/daemon.log").read().splitlines()
hits = [json.loads(l) for l in log if l.startswith("{") and '"gate_hit"' in l]
hit = hits[-1] if hits else {}
check("e2e1/gate_hit_present", bool(hits))
check("e2e1/gate_hit_pid_matches_child", hit.get("pid") == child.get("pid"))
check("e2e1/gate_hit_ppid_is_zygote", hit.get("ppid") == mzyg)
check("e2e1/gate_hit_name", hit.get("name") == "com.test.app")
check("e2e1/gate_hit_ack", hit.get("ack") is True)
check("e2e1/gate_hit_held_false", hit.get("held") is False)
check("e2e1/gate_hit_reverted", hit.get("reverted") is True)
check("e2e1/env_gate", child.get("env_gate") == "1")
check("e2e1/env_name", child.get("env_name") == "lspfrifa-zygote-test")
check("e2e1/env_ppid", child.get("env_ppid") == str(mzyg))
check("e2e1/env_proc", child.get("env_proc") == "com.test.app")
check("e2e1/original_setargv0_called", child.get("original_called") is True)
check("e2e1/original_setcontext_called", child.get("selinux_seen") is True)
check("e2e1/sigstop_seen", child.get("sigstop_seen") is True)
check("e2e1/child_pointer_restored", child.get("pointer_restored") is True)
check("e2e1/child_payload_restored", child.get("payload_restored") is True)
sys.exit(1 if failed else 0)
PY
  if [ $? -eq 0 ]; then
    grep '^PASS' "$S1/assert1.log"
    pass "scenario1 assertions"
  else
    cat "$S1/assert1.log"
    fail "scenario1 assertions"
  fi

  # daemon SIGTERM must revert the zygote patch
  kill -TERM "$DAEMON1_PID" 2>/dev/null
  wait "$DAEMON1_PID" 2>/dev/null
  DAEMON1_PID=""
  if grep -q "\"event\":\"revert\",\"pid\":$MZYG" "$S1/daemon.log"; then
    pass "daemon SIGTERM reverted zygote $MZYG"
  else
    fail "daemon SIGTERM reverted zygote $MZYG"
  fi

  # verify from inside the mock zygote that both patches are gone
  printf 'check\n' >&"${M1[1]}"
  if wait_for_file "$S1/check_result.json" 10; then
    python3 - "$S1" <<'PY' >"$S1/assert1b.log" 2>&1
import json, sys
s1 = sys.argv[1]
check_result = json.load(open(s1 + "/check_result.json"))
failed = 0
def check(name, condition):
    global failed
    print(("PASS " if condition else "FAIL ") + name)
    if not condition:
        failed += 1
check("e2e1b/zygote_pointer_restored", check_result.get("pointer_restored") is True)
check("e2e1b/zygote_payload_restored", check_result.get("payload_restored") is True)
sys.exit(1 if failed else 0)
PY
    if [ $? -eq 0 ]; then
      grep '^PASS' "$S1/assert1b.log"
      pass "scenario1 post-revert assertions"
    else
      cat "$S1/assert1b.log"
      fail "scenario1 post-revert assertions"
    fi
  else
    fail "mock zygote check result"
  fi
  printf 'quit\n' >&"${M1[1]}" 2>/dev/null
  wait "$MOCK1_PID" 2>/dev/null
  MOCK1_PID=""
fi

# ---------------------------------------------------------------------------
# Scenario 2: no daemon -> fail-open, env markers written, then revert
# ---------------------------------------------------------------------------
echo
echo "== E2E scenario 2: no daemon -> fail-open =="
S2="$TMP/s2"
mkdir -p "$S2/state"

coproc M2 { exec "$OUT/mock_zygote" "$S2" 2>"$S2/mock.err"; }
MOCK2_PID=$M2_PID

if read -t 10 -r READY_LINE2 <&"${M2[0]}"; then
  MZYG2=$(printf '%s\n' "$READY_LINE2" | awk '{print $2}')
  pass "mock_zygote ready (pid $MZYG2)"
else
  fail "mock_zygote ready"
  MZYG2=""
fi

if [ -n "$MZYG2" ]; then
  "$OUT/lspfrifa-zygote" inject --pid "$MZYG2" \
      --socket lspfrifa-zygote-failopen \
      --gate "$OUT/lspfrifa-gate.elf" \
      --libc-match libc.so.6 \
      --runtime-match libandroid_runtime.so \
      --selinux-match libselinux.so \
      --payload-match libstagefright.so \
      --state "$S2/state" >"$S2/inject.log" 2>&1
  check_rc $? 0 "inject without --serve"
  if grep -q '"event":"patch"' "$S2/inject.log"; then
    pass "scenario2 patch event"
  else
    cat "$S2/inject.log"
    fail "scenario2 patch event"
  fi

  printf 'go\n' >&"${M2[1]}"
  if wait_for_file "$S2/child_result.json" 15; then
    python3 - "$S2" "$MZYG2" <<'PY' >"$S2/assert2.log" 2>&1
import json, sys
s2, mzyg = sys.argv[1], int(sys.argv[2])
child = json.load(open(s2 + "/child_result.json"))
failed = 0
def check(name, condition):
    global failed
    print(("PASS " if condition else "FAIL ") + name)
    if not condition:
        failed += 1
check("e2e2/env_gate", child.get("env_gate") == "1")
check("e2e2/env_name", child.get("env_name") == "lspfrifa-zygote-failopen")
check("e2e2/env_ppid", child.get("env_ppid") == str(mzyg))
check("e2e2/env_proc", child.get("env_proc") == "com.test.app")
check("e2e2/original_setargv0_called", child.get("original_called") is True)
check("e2e2/original_setcontext_called", child.get("selinux_seen") is True)
check("e2e2/fail_open_no_sigstop", child.get("sigstop_seen") is False)
check("e2e2/child_still_patched", child.get("pointer_restored") is False)
sys.exit(1 if failed else 0)
PY
    if [ $? -eq 0 ]; then
      grep '^PASS' "$S2/assert2.log"
      pass "scenario2 fail-open assertions"
    else
      cat "$S2/assert2.log"
      fail "scenario2 fail-open assertions"
    fi
  else
    fail "scenario2 child wrote result"
  fi

  # persisted state must make a later revert work (daemon was never running)
  if [ -s "$S2/state/$MZYG2.patch" ]; then
    pass "scenario2 state file persisted"
  else
    fail "scenario2 state file persisted"
  fi
  "$OUT/lspfrifa-zygote" revert --pid "$MZYG2" --state "$S2/state" >"$S2/revert.log" 2>&1
  check_rc $? 0 "scenario2 revert --pid"
  if grep -q "\"event\":\"revert\",\"pid\":$MZYG2" "$S2/revert.log"; then
    pass "scenario2 revert event"
  else
    fail "scenario2 revert event"
  fi

  printf 'check\n' >&"${M2[1]}"
  if wait_for_file "$S2/check_result.json" 10; then
    python3 - "$S2" <<'PY' >"$S2/assert2b.log" 2>&1
import json, sys
s2 = sys.argv[1]
check_result = json.load(open(s2 + "/check_result.json"))
failed = 0
def check(name, condition):
    global failed
    print(("PASS " if condition else "FAIL ") + name)
    if not condition:
        failed += 1
check("e2e2b/zygote_pointer_restored", check_result.get("pointer_restored") is True)
check("e2e2b/zygote_payload_restored", check_result.get("payload_restored") is True)
sys.exit(1 if failed else 0)
PY
    if [ $? -eq 0 ]; then
      grep '^PASS' "$S2/assert2b.log"
      pass "scenario2 post-revert assertions"
    else
      cat "$S2/assert2b.log"
      fail "scenario2 post-revert assertions"
    fi
  else
    fail "scenario2 mock zygote check result"
  fi
  printf 'quit\n' >&"${M2[1]}" 2>/dev/null
  wait "$MOCK2_PID" 2>/dev/null
  MOCK2_PID=""
fi

# ---------------------------------------------------------------------------
# Scenario 3: --hold policy + `resume` control channel
# ---------------------------------------------------------------------------
echo
echo "== E2E scenario 3: --hold blocks the gate until resume =="
S3="$TMP/s3"
mkdir -p "$S3/state"

coproc M3 { exec "$OUT/mock_zygote" "$S3" 2>"$S3/mock.err"; }
MOCK3_PID=$M3_PID

if read -t 10 -r READY_LINE3 <&"${M3[0]}"; then
  MZYG3=$(printf '%s\n' "$READY_LINE3" | awk '{print $2}')
  pass "mock_zygote ready (pid $MZYG3)"
else
  fail "mock_zygote ready"
  MZYG3=""
fi

if [ -n "$MZYG3" ]; then
  "$OUT/lspfrifa-zygote" inject --pid "$MZYG3" --serve \
      --socket lspfrifa-zygote-hold \
      --gate "$OUT/lspfrifa-gate.elf" \
      --libc-match libc.so.6 \
      --runtime-match libandroid_runtime.so \
      --selinux-match libselinux.so \
      --payload-match libstagefright.so \
      --hold com.test.app \
      --state "$S3/state" >"$S3/daemon.log" 2>&1 &
  DAEMON3_PID=$!
  if wait_for_grep "$S3/daemon.log" '"event":"patch"' 10; then
    pass "scenario3 inject --hold (patch event)"
  else
    fail "scenario3 inject --hold"
  fi

  printf 'go\n' >&"${M3[1]}"
  if wait_for_grep "$S3/daemon.log" '"held":true' 15; then
    pass "scenario3 gate held (no ack yet)"
  else
    fail "scenario3 gate held"
    cat "$S3/daemon.log"
  fi
  sleep 0.4
  if [ ! -s "$S3/child_result.json" ]; then
    pass "scenario3 child still blocked while held"
  else
    fail "scenario3 child should still be blocked while held"
  fi

  HIT_PID=$(grep '"held":true' "$S3/daemon.log" | head -1 | \
      python3 -c 'import json,sys; print(json.load(sys.stdin)["pid"])' 2>/dev/null)
  if [ -n "$HIT_PID" ]; then
    pass "scenario3 gate_hit pid parsed ($HIT_PID)"
  else
    HIT_PID=0
    fail "scenario3 gate_hit pid parsed"
  fi

  "$OUT/lspfrifa-zygote" resume "$HIT_PID" --socket lspfrifa-zygote-hold \
      >"$S3/resume.log" 2>&1
  check_rc $? 0 "scenario3 resume <pid>"

  if wait_for_file "$S3/child_result.json" 15; then
    pass "scenario3 child continued after resume"
  else
    fail "scenario3 child continued after resume"
  fi
  if wait_for_grep "$S3/daemon.log" '"event":"gate_resume"' 10; then
    pass "scenario3 daemon gate_resume event"
  else
    fail "scenario3 daemon gate_resume event"
  fi

  python3 - "$S3" "$HIT_PID" <<'PY' >"$S3/assert3.log" 2>&1
import json, sys
s3, hit_pid = sys.argv[1], int(sys.argv[2])
failed = 0
def check(name, condition):
    global failed
    print(("PASS " if condition else "FAIL ") + name)
    if not condition:
        failed += 1
try:
    child = json.load(open(s3 + "/child_result.json"))
except Exception as e:
    print("FAIL e2e3/child_result_readable (%s)" % e); sys.exit(1)
lines = open(s3 + "/daemon.log").read().splitlines()
held_hits = [json.loads(l) for l in lines if l.startswith("{") and '"gate_hit"' in l and '"held":true' in l]
resumes = [json.loads(l) for l in lines if l.startswith("{") and '"gate_resume"' in l]
check("e2e3/held_hit_present", bool(held_hits))
check("e2e3/held_ack_false", bool(held_hits) and held_hits[-1].get("ack") is False)
check("e2e3/held_no_revert_yet", bool(held_hits) and held_hits[-1].get("reverted") is False)
check("e2e3/resume_pid", bool(resumes) and resumes[-1].get("pid") == hit_pid)
check("e2e3/resume_ack", bool(resumes) and resumes[-1].get("ack") is True)
check("e2e3/resume_reverted", bool(resumes) and resumes[-1].get("reverted") is True)
check("e2e3/env_gate", child.get("env_gate") == "1")
check("e2e3/env_name", child.get("env_name") == "lspfrifa-zygote-hold")
check("e2e3/sigstop_seen", child.get("sigstop_seen") is True)
check("e2e3/child_pointer_restored", child.get("pointer_restored") is True)
sys.exit(1 if failed else 0)
PY
  if [ $? -eq 0 ]; then
    grep '^PASS' "$S3/assert3.log"
    pass "scenario3 assertions"
  else
    cat "$S3/assert3.log"
    fail "scenario3 assertions"
  fi

  kill -TERM "$DAEMON3_PID" 2>/dev/null
  wait "$DAEMON3_PID" 2>/dev/null
  DAEMON3_PID=""
  if grep -q "\"event\":\"revert\",\"pid\":$MZYG3" "$S3/daemon.log"; then
    pass "scenario3 daemon SIGTERM reverted zygote $MZYG3"
  else
    fail "scenario3 daemon SIGTERM reverted zygote $MZYG3"
  fi
  printf 'quit\n' >&"${M3[1]}" 2>/dev/null
  wait "$MOCK3_PID" 2>/dev/null
  MOCK3_PID=""
fi

echo
echo "=================================================="
echo "PASS: $PASS_COUNT  FAIL: $FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
  echo "RESULT: FAIL"
  exit 1
fi
echo "RESULT: PASS"
exit 0
