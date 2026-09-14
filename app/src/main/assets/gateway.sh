# Sourced after validated, shell-quoted variables; never enable shell tracing.
MOUNTINFO=${MOUNTINFO:-/proc/self/mountinfo}
fail() { echo "$1"; exit 20; }
mounted() { awk -v p="$1" '$5 == p { found=1 } END { exit !found }' "$MOUNTINFO"; }
owned() {
    awk -v p="$1" '
    $5 == p {
        count++
        for (i=7; i<=NF; i++) if ($i == "-" && $(i+1) ~ /^fuse/ && $(i+2) == "nas-photos-gateway") valid++
    }
    END { exit !(count == 1 && valid == 1) }' "$MOUNTINFO"
}
readonly_mount() {
    awk -v p="$1" '$5 == p && ("," $6 ",") ~ /,ro,/ { found=1 } END { exit !found }' "$MOUNTINFO"
}
guard_mount() {
    if mounted "$1"; then owned "$1" || fail '目录已有其他挂载，已停止操作'; fi
}
guard_path() {
    current="$1"
    while [ "$current" != / ] && [ "$current" != /storage/emulated/0 ]; do
        [ ! -L "$current" ] || fail '挂载路径包含符号链接，已停止操作'
        current=$(dirname "$current")
    done
}
prepare_empty() {
    guard_path "$1"
    mkdir -p "$1"
    contents=$(ls -A "$1") || fail '无法读取挂载目录，已停止操作'
    [ -z "$contents" ] || fail '挂载目录含有本地文件，请使用独立空目录'
}
test_connection() {
    "$RCLONE" lsf "$REMOTE" --max-depth 1 --config /dev/null \
        --contimeout 5s --timeout 15s --retries 1 --low-level-retries 1 >/dev/null
    echo 'SMB_CONNECTION_OK'
}
mount_gateway() {
    [ "$(id -u)" = 0 ] || fail '请授予 Magisk Root 权限'
    [ -c /dev/fuse ] || fail '设备缺少 /dev/fuse'
    guard_path "$SOURCE"
    guard_path "$TARGET"
    guard_mount "$SOURCE"
    guard_mount "$TARGET"
    if ! mounted "$SOURCE"; then
        prepare_empty "$SOURCE"
        "$RCLONE" mount "$REMOTE" "$SOURCE" --config /dev/null \
            --devname nas-photos-gateway --read-only --allow-other \
            --uid 1023 --gid 1023 --dir-perms 0555 --file-perms 0444 \
            --vfs-cache-mode off --buffer-size 4M --dir-cache-time 5m \
            --contimeout 5s --timeout 15s --retries 1 --low-level-retries 1 \
            --daemon --daemon-wait 20s --log-level ERROR
    fi
    owned "$SOURCE" && readonly_mount "$SOURCE" || fail 'FUSE 挂载未通过只读检查'
    if ! mounted "$TARGET"; then
        prepare_empty "$TARGET"
        if ! mount --bind "$SOURCE" "$TARGET"; then
            echo 'bind mount 失败；FUSE 已保留，可使用卸载清理'
            exit 21
        fi
        mount -o remount,bind,ro "$TARGET" || fail '只读 bind 设置失败，请卸载后检查系统兼容性'
    fi
    owned "$TARGET" && readonly_mount "$TARGET" || fail 'bind 挂载未通过只读检查，请卸载'
    echo 'MOUNT_OK'
}
unmount_gateway() {
    # Validate both before changing either. No lazy/force unmount and no deletion.
    guard_mount "$TARGET"
    guard_mount "$SOURCE"
    if mounted "$TARGET"; then umount "$TARGET" || fail '目录被占用，请暂停 Google Photos 读取后重试'; fi
    if mounted "$SOURCE"; then umount "$SOURCE" || fail 'FUSE 被占用，请稍后重试'; fi
    ! mounted "$TARGET" && ! mounted "$SOURCE" || fail '仍检测到挂载'
    echo 'UNMOUNT_OK'
}
status_gateway() {
    echo "ROOT_UID=$(id -u)"
    if [ -x "$RCLONE" ]; then "$RCLONE" version | head -n 1; else echo 'RCLONE_MISSING'; fi
    if [ -c /dev/fuse ]; then echo 'FUSE_DEVICE_OK'; else echo 'FUSE_DEVICE_MISSING'; fi
    for point in "$SOURCE" "$TARGET"; do
        if owned "$point"; then
            if readonly_mount "$point"; then echo "READONLY $point"; else echo "NOT_READONLY $point"; fi
        elif mounted "$point"; then echo "FOREIGN_MOUNT $point"
        else echo "UNMOUNTED $point"; fi
    done
}
