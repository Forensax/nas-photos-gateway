#!/usr/bin/env bash
set -euo pipefail
SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/gateway.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
export MOUNTINFO="$TMP/mountinfo"
export SOURCE=/mnt/nas-photos-gateway
export TARGET=/storage/emulated/0/DCIM/NAS
export RCLONE=/does/not/exist
touch "$MOUNTINFO"
source "$SCRIPT"
mounted "$TARGET" && exit 1
printf '10 1 0:1 / %s ro - fuse.rclone nas-photos-gateway ro\n' "$TARGET" > "$MOUNTINFO"
owned "$TARGET"
readonly_mount "$TARGET"
printf '11 1 0:2 / %s ro - fuse.rclone other-source ro\n' "$SOURCE" >> "$MOUNTINFO"
if (guard_mount "$SOURCE"); then echo 'FAIL: foreign mount accepted'; exit 1; fi
# Foreign source prevents *any* unmount, including an otherwise-owned target.
umount() { touch "$TMP/unmount-called"; }
if (unmount_gateway); then echo 'FAIL: unsafe unmount accepted'; exit 1; fi
test ! -e "$TMP/unmount-called"
# Stacked mounts must be rejected even when one layer carries our marker.
printf '12 1 0:3 / %s rw - tmpfs tmpfs rw\n' "$TARGET" >> "$MOUNTINFO"
if (guard_mount "$TARGET"); then echo 'FAIL: stacked mount accepted'; exit 1; fi
mkdir "$TMP/nonempty"
touch "$TMP/nonempty/photo.jpg"
if (prepare_empty "$TMP/nonempty"); then echo 'FAIL: nonempty target accepted'; exit 1; fi
ln -s "$TMP/nonempty" "$TMP/link"
if (prepare_empty "$TMP/link"); then echo 'FAIL: symlink target accepted'; exit 1; fi
test -e "$TMP/nonempty/photo.jpg"
printf '10 1 0:1 / %s rw - fuse.rclone nas-photos-gateway rw\n' "$TARGET" > "$MOUNTINFO"
if readonly_mount "$TARGET"; then echo 'FAIL: writable mount accepted'; exit 1; fi
echo 'Root safety checks passed'
