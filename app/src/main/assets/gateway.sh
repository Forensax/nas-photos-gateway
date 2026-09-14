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
select_bindpoint() {
    BINDPOINT="$TARGET"
    RUNTIME_POINTS=""
    # Android 9/10 sdcardfs exposes shared runtime mounts to app namespaces.
    # /storage is a slave mount: binding there alone never propagates back.
    if awk '$5=="/mnt/runtime/default/emulated" {for(i=7;i<=NF;i++) if($i=="-" && $(i+1)=="sdcardfs") ok=1} END {exit !ok}' "$MOUNTINFO"; then
        relative=${TARGET#/storage/emulated/0}
        BINDPOINT="/mnt/runtime/default/emulated/0$relative"
        for mode in default read write full; do
            RUNTIME_POINTS="$RUNTIME_POINTS /mnt/runtime/$mode/emulated/0$relative"
        done
    fi
}
guard_all_mounts() {
    for point in "$SOURCE" "$TARGET" $RUNTIME_POINTS; do guard_mount "$point"; done
}
bind_gateway() {
    select_bindpoint
    guard_all_mounts
    owned "$SOURCE" && readonly_mount "$SOURCE" || fail 'FUSE 挂载未通过只读检查'
    if ! mounted "$BINDPOINT"; then
        prepare_empty "$BINDPOINT"
        migrated=false
        if [ "$BINDPOINT" != "$TARGET" ] && mounted "$TARGET"; then
            umount "$TARGET" || fail '旧映射正在使用，请稍后重试'
            migrated=true
        fi
        if ! mount --bind "$SOURCE" "$BINDPOINT"; then
            if [ "$migrated" = true ]; then mount --bind "$SOURCE" "$TARGET" || fail '映射恢复失败，请检查诊断'; fi
            fail 'bind mount 失败；FUSE 已保留，可使用卸载清理'
        fi
        if ! readonly_mount "$BINDPOINT"; then
            mount -o remount,bind,ro "$BINDPOINT" || fail '只读 bind 设置失败，请卸载后检查系统兼容性'
        fi
    fi
    owned "$BINDPOINT" && readonly_mount "$BINDPOINT" || fail '共享 bind 挂载未通过只读检查'
    owned "$TARGET" && readonly_mount "$TARGET" || fail '目录映射未传播到应用路径，请检查诊断'
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
    select_bindpoint
    guard_all_mounts
    if ! mounted "$SOURCE"; then
        prepare_empty "$SOURCE"
        "$RCLONE" mount "$REMOTE" "$SOURCE" --config /dev/null \
            --devname nas-photos-gateway --read-only --allow-other \
            --uid 1023 --gid 1023 --dir-perms 0555 --file-perms 0444 \
            --vfs-cache-mode off --buffer-size 4M --dir-cache-time 5m \
            --contimeout 5s --timeout 15s --retries 1 --low-level-retries 1 \
            --daemon --daemon-wait 20s --log-level ERROR
    fi
    bind_gateway
    echo 'MOUNT_OK'
}
unmount_gateway() {
    # Unmount the shared parent first so all app-visible replicas disappear.
    select_bindpoint
    guard_all_mounts
    if mounted "$BINDPOINT"; then umount "$BINDPOINT" || fail '目录被占用，请暂停 Google Photos 读取后重试'; fi
    for point in $RUNTIME_POINTS "$TARGET"; do
        if mounted "$point"; then umount "$point" || fail '仍有目录映射被占用，请稍后重试'; fi
    done
    if mounted "$SOURCE"; then umount "$SOURCE" || fail 'FUSE 被占用，请稍后重试'; fi
    for point in "$SOURCE" "$TARGET" $RUNTIME_POINTS; do
        if mounted "$point"; then fail '仍检测到挂载'; fi
    done
    echo 'UNMOUNT_OK'
}
status_gateway() {
    select_bindpoint
    echo "ROOT_UID=$(id -u)"
    echo "BINDPOINT=$BINDPOINT"
    if [ -x "$RCLONE" ]; then "$RCLONE" version | head -n 1; else echo 'RCLONE_MISSING'; fi
    if [ -c /dev/fuse ]; then echo 'FUSE_DEVICE_OK'; else echo 'FUSE_DEVICE_MISSING'; fi
    for point in "$SOURCE" "$TARGET" $RUNTIME_POINTS; do
        if owned "$point"; then
            if readonly_mount "$point"; then echo "READONLY $point"; else echo "NOT_READONLY $point"; fi
        elif mounted "$point"; then echo "FOREIGN_MOUNT $point"
        else echo "UNMOUNTED $point"; fi
    done
}

# Only the daemon whose exact executable, remote and mountpoint match this
# configuration may receive HUP. Do not signal the module's rclone Web UI.
gateway_daemon() {
    match=''
    for pid in $(pidof rclone 2>/dev/null || true); do
        case "$pid" in ''|*[!0-9]*) continue ;; esac
        args=$(tr '\000' '\n' < "${PROC_ROOT:-/proc}/$pid/cmdline" 2>/dev/null) || continue
        if printf '%s\n' "$args" | awk -v binary="$RCLONE" -v remote="$REMOTE" -v source="$SOURCE" '
            NR==1 && $0==binary { ok++ }
            NR==2 && $0=="mount" { ok++ }
            NR==3 && $0==remote { ok++ }
            NR==4 && $0==source { ok++ }
            previous=="--devname" && $0=="nas-photos-gateway" { named=1 }
            { previous=$0 }
            END { exit !(ok==4 && named==1) }'; then
            [ -z "$match" ] || fail '存在多个匹配的 rclone 进程，已停止扫描'
            match="$pid"
        fi
    done
    [ -n "$match" ] || fail '当前挂载与保存的 NAS 配置不匹配，请卸载后重新挂载'
    printf '%s\n' "$match"
}

scan_identity() {
    select_bindpoint
    guard_all_mounts
    guard_path "$SOURCE"
    guard_path "$TARGET"
    for point in "$SOURCE" "$TARGET" $RUNTIME_POINTS; do
        owned "$point" && readonly_mount "$point" || fail '扫描需要完整的只读挂载'
    done
    source_device=$(awk -v p="$SOURCE" '$5==p {print $3}' "$MOUNTINFO")
    target_device=$(awk -v p="$TARGET" '$5==p {print $3}' "$MOUNTINFO")
    [ "$source_device" = "$target_device" ] || fail '源目录与应用目录的挂载不一致'
    source_id=$(awk -v p="$SOURCE" '$5==p {print $1}' "$MOUNTINFO")
    target_id=$(awk -v p="$TARGET" '$5==p {print $1}' "$MOUNTINFO")
    daemon=$(gateway_daemon) || exit 20
    printf '%s:%s:%s:%s\n' "$source_id" "$target_id" "$source_device" "$daemon"
}

snapshot_gateway() {
    token=$(scan_identity) || exit 20
    printf 'GATEWAY_SNAPSHOT %s\n' "$token"
    # A new SMB client bypasses the mount's cached directory listing. Include
    # directories and hidden files so presence can never be mistaken for deletion.
    "$RCLONE" lsjson "$REMOTE" --recursive --no-modtime --no-mimetype \
        --config /dev/null --contimeout 5s --timeout 15s --retries 1 --low-level-retries 1 || exit 21
    after=$(scan_identity) || exit 20
    [ "$token" = "$after" ] || fail '扫描期间挂载发生变化，已停止操作'
    printf '\nGATEWAY_SNAPSHOT_END %s\n' "$token"
}

prepare_scan() {
    token=$(scan_identity) || exit 20
    daemon=$(gateway_daemon) || exit 20
    kill -HUP "$daemon" || fail '刷新 rclone 目录缓存失败'
    snapshot_gateway
}
