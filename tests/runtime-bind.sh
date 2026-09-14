#!/usr/bin/env bash
set -euo pipefail
SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/gateway.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
MOUNTINFO="$TMP/mountinfo"
SOURCE=/mnt/nas-photos-gateway
TARGET=/storage/emulated/0/DCIM/NAS
RCLONE=/unused
source "$SCRIPT"
base_table() {
    printf '1 0 0:28 / /mnt/runtime/default/emulated rw shared:13 - sdcardfs /data/media rw\n' > "$MOUNTINFO"
    row "$SOURCE"
}
row() { printf '2 1 0:29 / %s ro shared:14 - fuse.rclone nas-photos-gateway ro\n' "$1" >> "$MOUNTINFO"; }
remove_row() { awk -v p="$1" '$5!=p' "$MOUNTINFO" > "$TMP/new"; cat "$TMP/new" > "$MOUNTINFO"; }
prepare_empty() { echo "prepare $1" >> "$TMP/operations"; }
mount() {
    echo "mount $*" >> "$TMP/operations"
    if [ "${SIMULATE_FAILURE:-false}" = true ] && [ "$3" = "$BINDPOINT" ]; then return 1; fi
    row "$3"
    if [ "$3" = "$BINDPOINT" ]; then
        for point in $RUNTIME_POINTS "$TARGET"; do [ "$point" = "$3" ] || row "$point"; done
    fi
}
umount() {
    echo "umount $1" >> "$TMP/operations"
    remove_row "$1"
    if [ "$1" = "$BINDPOINT" ]; then for point in $RUNTIME_POINTS "$TARGET"; do remove_row "$point"; done; fi
}
base_table
select_bindpoint
test "$BINDPOINT" = /mnt/runtime/default/emulated/0/DCIM/NAS
# Migration from the original slave-only bind reaches all peers.
row "$TARGET"
bind_gateway
for point in "$TARGET" $RUNTIME_POINTS; do owned "$point" && readonly_mount "$point"; done
count=$(wc -l < "$TMP/operations")
bind_gateway
test "$count" = "$(wc -l < "$TMP/operations")"
unmount_gateway
for point in "$SOURCE" "$TARGET" $RUNTIME_POINTS; do if mounted "$point"; then exit 1; fi; done
# A foreign runtime peer prevents all mutations.
base_table
printf '3 1 0:30 / /mnt/runtime/read/emulated/0/DCIM/NAS ro - fuse.rclone foreign ro\n' >> "$MOUNTINFO"
before=$(wc -l < "$TMP/operations")
if (bind_gateway); then exit 1; fi
if (unmount_gateway); then exit 1; fi
test "$before" = "$(wc -l < "$TMP/operations")"
# Failed migration restores the old private bind and preserves the FUSE source.
base_table
row "$TARGET"
SIMULATE_FAILURE=true
if (bind_gateway); then exit 1; fi
owned "$TARGET"
owned "$SOURCE"
if mounted "$BINDPOINT"; then exit 1; fi
SIMULATE_FAILURE=false
# Devices without sdcardfs retain the direct bind strategy.
printf '' > "$MOUNTINFO"
select_bindpoint
test "$BINDPOINT" = "$TARGET"
test -z "$RUNTIME_POINTS"
echo 'Runtime propagation regression checks passed'
