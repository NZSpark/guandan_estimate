#!/usr/bin/env bash
# adb-wifi.sh — 把已连 USB 的 Android 设备切到 WiFi 调试，或重新连回无线设备。
#
# 用法：
#   scripts/adb-wifi.sh               # 自动：有 USB 设备就走 tcpip+connect，否则尝试连已记录的设备
#   scripts/adb-wifi.sh on            # 强制走 USB 打开 tcpip 并连接
#   scripts/adb-wifi.sh off           # 断开无线并恢复 USB 模式（需 USB 或无线在线）
#   scripts/adb-wifi.sh status        # 打印当前设备与无线端口状态
#   scripts/adb-wifi.sh --serial X ...  # 指定设备序列号（USB 序列或 ip:port）
#
# 环境变量：
#   ADB_PORT=5555            无线调试端口
#   ADB_SERIAL=<serial>      等价于 --serial
#   ADB_HOST_IFACE=en0       用于提示的宿主网卡（仅信息展示）

set -uo pipefail

PORT="${ADB_PORT:-5555}"
SERIAL="${ADB_SERIAL:-}"
STATE_FILE="${TMPDIR:-/tmp}/adb-wifi.last"
ADB="${ADB:-adb}"

log()  { printf '%s\n' "$*" >&2; }
info() { printf '[adb-wifi] %s\n' "$*"; }
die()  { printf '[adb-wifi] ERROR: %s\n' "$*" >&2; exit 1; }

need_adb() { command -v "$ADB" >/dev/null 2>&1 || die "找不到 adb，请先装 Android SDK Platform Tools"; }

# 输出所有在线设备序列号（含 ip:port 形式）
list_devices() { "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'; }

is_wifi_serial() { case "$1" in *:* ) return 0;; *) return 1;; esac; }

# 拿设备的 wlan0 IPv4
wlan_ip_of() {
  local s="$1"
  "$ADB" -s "$s" shell ip -f inet addr show wlan0 2>/dev/null \
    | awk '/inet /{sub(/\/.*/,"",$2); print $2; exit}'
}

# 在已连接设备里挑一个：优先指定 SERIAL，其次 USB，其次无线
pick_target() {
  local devices usb wifi
  devices="$(list_devices)"
  [ -n "$devices" ] || return 1

  if [ -n "$SERIAL" ]; then
    printf '%s\n' "$devices" | grep -Fxq "$SERIAL" || return 1
    printf '%s\n' "$SERIAL"; return 0
  fi

  usb="$(printf '%s\n' "$devices" | grep -v ':' | head -1)"
  [ -n "$usb" ] && { printf '%s\n' "$usb"; return 0; }

  wifi="$(printf '%s\n' "$devices" | grep ':' | head -1)"
  [ -n "$wifi" ] && { printf '%s\n' "$wifi"; return 0; }
  return 1
}

cmd_status() {
  info "adb version: $("$ADB" version | awk 'NR==1{print $5}')"
  info "devices:"
  "$ADB" devices -l | sed 's/^/    /'
  local d
  while read -r d; do
    [ -n "$d" ] || continue
    local ip port model
    ip="$(wlan_ip_of "$d")"
    port="$("$ADB" -s "$d" shell getprop service.adb.tcp.port 2>/dev/null | tr -d '\r')"
    model="$("$ADB" -s "$d" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
    info "  $d  model=${model:-?}  wlan0=${ip:-?}  tcp.port=${port:-0}"
  done <<< "$(list_devices)"
  if [ -f "$STATE_FILE" ]; then
    info "last wifi target: $(cat "$STATE_FILE")"
  fi
}

# 等待 ip:port 变为在线，最多约 15s
wait_online() {
  local addr="$1" i
  for i in $(seq 1 15); do
    if "$ADB" devices | awk -v a="$addr" 'NR>1 && $1==a && $2=="device"{f=1} END{exit !f}'; then
      return 0
    fi
    sleep 1
  done
  return 1
}

connect_addr() {
  local addr="$1"
  info "adb connect $addr"
  "$ADB" connect "$addr" >/dev/null 2>&1
  if wait_online "$addr"; then
    printf '%s\n' "$addr" > "$STATE_FILE"
    info "已连接：$addr（记录到 $STATE_FILE）"
    return 0
  fi
  return 1
}

cmd_on() {
  local target ip addr

  target="$(pick_target)" || die "没有可用设备。请先用 USB 连上手机（adb devices 能看到）"
  if is_wifi_serial "$target"; then
    info "目标已是无线设备：$target"
    cmd_status
    return 0
  fi

  ip="$(wlan_ip_of "$target")"
  [ -n "$ip" ] || die "设备 $target 读不到 wlan0 IP，请确认手机已连 WiFi"
  info "设备 $target  wlan0=$ip"

  info "开启 TCP 模式：adb -s $target tcpip $PORT"
  "$ADB" -s "$target" tcpip "$PORT" >/dev/null 2>&1 \
    || die "tcpip 命令失败（需要 USB 调试授权）"

  # 设备会重启 adbd，稍等端口起来
  sleep 2
  addr="$ip:$PORT"

  if ! connect_addr "$addr"; then
    # 常见坑：adb server 缓存了旧连接状态，重启 server 再试
    info "首次连接失败，重启 adb server 后重试"
    "$ADB" kill-server >/dev/null 2>&1
    sleep 1
    "$ADB" start-server >/dev/null 2>&1
    sleep 2
    connect_addr "$addr" || die "仍无法连接 $addr。检查：手机与电脑同网段、端口 $PORT 未被拦、手机 WiFi 未休眠"
  fi

  info "完成。日常可直接：adb connect $addr"
  info "现在可以用：adb -s $addr <command>"
}

cmd_off() {
  local target
  target="$(pick_target)" || die "没有可用设备，无法恢复 USB 模式"

  if is_wifi_serial "$target"; then
    info "恢复 USB 模式：adb -s $target usb"
    "$ADB" -s "$target" usb >/dev/null 2>&1 || log "usb 命令失败（可能已拔线，忽略）"
    "$ADB" disconnect "$target" >/dev/null 2>&1
  else
    info "目标 $target 已是 USB 设备，执行 adb usb"
    "$ADB" -s "$target" usb >/dev/null 2>&1 || log "usb 命令失败（忽略）"
  fi

  # 断开所有无线连接
  local d
  while read -r d; do
    is_wifi_serial "$d" && { info "disconnect $d"; "$ADB" disconnect "$d" >/dev/null 2>&1; }
  done <<< "$(list_devices)"
  rm -f "$STATE_FILE"
  info "已关闭无线调试"
}

main() {
  need_adb

  local cmd=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --serial) SERIAL="${2:-}"; shift 2 ;;
      --serial=*) SERIAL="${1#*=}"; shift ;;
      --port) PORT="${2:-}"; shift 2 ;;
      --port=*) PORT="${1#*=}"; shift ;;
      -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "$0"; exit 0 ;;
      on|off|status) cmd="$1"; shift ;;
      *) die "未知参数：$1（用 --help 看用法）" ;;
    esac
  done
  [ -n "$cmd" ] || cmd="on"

  case "$cmd" in
    on)     cmd_on ;;
    off)    cmd_off ;;
    status) cmd_status ;;
  esac
}

main "$@"
