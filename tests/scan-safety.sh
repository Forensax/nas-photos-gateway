#!/usr/bin/env bash
set -euo pipefail
SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/gateway.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
export MOUNTINFO="$TMP/mountinfo"
export PROC_ROOT="$TMP/proc"
SOURCE=/mnt/nas-photos-gateway
TARGET=/storage/emulated/0/DCIM/NAS
REMOTE='nas:photo/test'
RCLONE="$TMP/rclone"
mkdir -p "$PROC_ROOT/101" "$PROC_ROOT/102" "$PROC_ROOT/103"
printf '%s\0' "$RCLONE" mount "$REMOTE" "$SOURCE" --devname nas-photos-gateway > "$PROC_ROOT/101/cmdline"
printf '%s\0' "$RCLONE" rcd --rc-web-gui > "$PROC_ROOT/102/cmdline"
cat > "$RCLONE" <<'RCLONE'
#!/usr/bin/env bash
set -eu
test "$1" = lsjson
test "$2" = nas:photo/test
case "${SIMULATE:-ok}" in
    offline) echo 'connection refused' >&2; exit 1 ;;
    partial) echo '['; exit 1 ;;
    changed) sed 's/^11 /12 /' "$MOUNTINFO" > "$MOUNTINFO.new"; cat "$MOUNTINFO.new" > "$MOUNTINFO" ;;
esac
echo '[]'
RCLONE
chmod +x "$RCLONE"
pidof() { echo "${TEST_PIDS:-101 102}"; }
kill() { printf '%s %s\n' "$1" "$2" >> "$TMP/signals"; }
source "$SCRIPT"
base_table() {
    printf '10 1 0:29 / %s ro - fuse.rclone nas-photos-gateway ro\n' "$SOURCE" > "$MOUNTINFO"
    printf '11 1 0:29 / %s ro - fuse.rclone nas-photos-gateway ro\n' "$TARGET" >> "$MOUNTINFO"
}
base_table
prepare_scan > "$TMP/healthy"
grep -q '"end":"10:11:0:29:101:ro"}' "$TMP/healthy"
test "$(cat "$TMP/signals")" = '-HUP 101'
# Neither a failed connection nor a partial listing may acquire a completion marker.
for failure in offline partial changed; do
    base_table
    export SIMULATE="$failure"
    if (snapshot_gateway) > "$TMP/failed" 2>/dev/null; then echo "FAIL: $failure accepted"; exit 1; fi
    if grep -q '"end":' "$TMP/failed"; then echo 'FAIL: failed listing marked complete'; exit 1; fi
done
export SIMULATE=ok
base_table
# A different saved remote must not flush or reconcile the active mount.
before=$(wc -l < "$TMP/signals")
if (REMOTE=nas:other; prepare_scan) > /dev/null; then echo 'FAIL: remote mismatch accepted'; exit 1; fi
# Duplicate mount daemons and foreign mounts fail before sending any signal.
cp "$PROC_ROOT/101/cmdline" "$PROC_ROOT/103/cmdline"
if (TEST_PIDS='101 102 103'; prepare_scan) > /dev/null; then echo 'FAIL: duplicate daemon accepted'; exit 1; fi
printf '12 1 0:30 / %s ro - fuse.rclone foreign ro\n' "$TARGET" >> "$MOUNTINFO"
if (prepare_scan) > /dev/null; then echo 'FAIL: foreign mount accepted'; exit 1; fi
base_table
sed 's/^11 1 0:29/11 1 0:30/' "$MOUNTINFO" > "$TMP/new"
cat "$TMP/new" > "$MOUNTINFO"
if (prepare_scan) > /dev/null; then echo 'FAIL: unrelated FUSE target accepted'; exit 1; fi
test "$before" = "$(wc -l < "$TMP/signals")"
base_table
sed 's/ ro / rw /g; s/ ro$/ rw/' "$MOUNTINFO" > "$TMP/new"
cat "$TMP/new" > "$MOUNTINFO"
if (prepare_scan) >/dev/null; then echo 'FAIL: mode mismatch accepted'; exit 1; fi
ALLOW_DELETE=true prepare_scan > "$TMP/writable"
grep -q '"end":"10:11:0:29:101:rw"}' "$TMP/writable"
echo 'Scan inventory safety checks passed'
