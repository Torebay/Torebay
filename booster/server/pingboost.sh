#!/usr/bin/env bash
# PingBoost: серверный помощник. Ставит на VPS (Ubuntu/Debian) WireGuard и маленькую
# UDP-службу для замера пинга, выдаёт готовые конфиги для приложения.
#
# Работает РЯДОМ с уже установленным VPN: у него свой интерфейс (wgboost), свой UDP-порт и
# своя подсеть, существующие настройки не затрагиваются.
#
#   sudo bash pingboost.sh install                      установка и первый конфиг
#   sudo pingboost add-client <имя> [название сервера]  ещё один конфиг (ещё один телефон)
#   sudo pingboost remove-client <имя>
#   sudo pingboost show <имя>                           показать конфиг ещё раз
#   sudo pingboost list | status
#
# Параметры установки (переменные окружения, все необязательные):
#   WG_PORT=51830     UDP-порт WireGuard
#   PING_PORT=51831   UDP-порт службы замера пинга
#   WG_NET=10.77.0    подсеть туннеля (/24)
#   ENDPOINT=1.2.3.4  внешний адрес сервера, если автоопределение ошиблось
#   SERVER_NAME=Германия  как сервер будет называться в приложении

set -euo pipefail

STATE_DIR="${PINGBOOST_STATE_DIR:-/etc/pingboost}"
WG_DIR="${PINGBOOST_WG_DIR:-/etc/wireguard}"
ENV_FILE="$STATE_DIR/env"
CLIENT_DIR="$STATE_DIR/clients"
SELF_BIN=/usr/local/bin/pingboost
ECHO_BIN=/usr/local/bin/pingboost-echo
ECHO_UNIT=/etc/systemd/system/pingboost-echo.service

die() { echo "Ошибка: $*" >&2; exit 1; }

need_root() { [ "$(id -u)" -eq 0 ] || die "запусти от root (sudo)"; }

load_env() {
  [ -f "$ENV_FILE" ] || die "PingBoost ещё не установлен: sudo bash $0 install"
  # shellcheck disable=SC1090
  . "$ENV_FILE"
}

# grep без -q: он дочитывает ввод до конца, иначе при pipefail возможен ложный отказ из-за SIGPIPE.
port_busy() { ss -lunH 2>/dev/null | awk '{print $4}' | grep -E "[:.]$1\$" >/dev/null; }

valid_client_name() { [[ "${1:-}" =~ ^[A-Za-z0-9_-]{1,24}$ ]]; }

cmd_install() {
  need_root
  command -v apt-get >/dev/null || die "поддерживаются только Ubuntu и Debian (нужен apt-get)"
  command -v systemctl >/dev/null || die "нужен systemd"
  [ ! -f "$ENV_FILE" ] || die "уже установлено. Новый конфиг: pingboost add-client <имя>"

  local wg_if="${WG_IF:-wgboost}"
  local wg_port="${WG_PORT:-51830}"
  local ping_port="${PING_PORT:-51831}"
  local wg_net="${WG_NET:-10.77.0}"
  [[ "$wg_net" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] || die "WG_NET должен быть вида 10.77.0"
  [[ "$wg_port" =~ ^[0-9]+$ && "$ping_port" =~ ^[0-9]+$ ]] || die "порты должны быть числами"

  if command -v ss >/dev/null; then
    port_busy "$wg_port" && die "UDP-порт $wg_port занят. Выбери другой: WG_PORT=51840 sudo -E bash $0 install"
    port_busy "$ping_port" && die "UDP-порт $ping_port занят. Выбери другой: PING_PORT=51841 sudo -E bash $0 install"
  fi
  if command -v ip >/dev/null; then
    local routes
    routes="$(ip -4 route show || true)"
    [[ "$routes" != *"${wg_net}.0/24"* ]] || die "подсеть ${wg_net}.0/24 уже занята. Выбери другую: WG_NET=10.88.0"
    if ip link show "$wg_if" >/dev/null 2>&1; then die "интерфейс $wg_if уже существует"; fi
  fi

  echo "==> Ставлю пакеты (wireguard, iptables, python3)"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y
  apt-get install -y wireguard wireguard-tools iptables python3 curl
  modprobe wireguard 2>/dev/null || true

  local iface
  iface="$(ip -4 route show default | awk '{for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit }}')"
  [ -n "$iface" ] || die "не нашёл сетевой интерфейс по умолчанию"

  local endpoint="${ENDPOINT:-}"
  [ -n "$endpoint" ] || endpoint="$(curl -4 -fsS --max-time 6 https://api.ipify.org 2>/dev/null || true)"
  [ -n "$endpoint" ] || endpoint="$(ip -4 -o addr show dev "$iface" | awk '{print $4}' | cut -d/ -f1 | head -n1)"
  [ -n "$endpoint" ] || die "не удалось определить внешний IP, задай его: ENDPOINT=1.2.3.4"

  echo "==> Создаю ключи и конфиг WireGuard ($wg_if, порт $wg_port/udp, сеть ${wg_net}.0/24)"
  mkdir -p "$STATE_DIR" "$CLIENT_DIR" "$WG_DIR"
  chmod 700 "$STATE_DIR" "$CLIENT_DIR"
  ( umask 077; wg genkey | tee "$STATE_DIR/server.key" | wg pubkey > "$STATE_DIR/server.pub" )

  (
    umask 077
    cat > "$WG_DIR/${wg_if}.conf" <<EOF
[Interface]
Address = ${wg_net}.1/24
ListenPort = ${wg_port}
PrivateKey = $(cat "$STATE_DIR/server.key")
PostUp = iptables -I FORWARD 1 -i %i -j ACCEPT; iptables -I FORWARD 1 -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -A POSTROUTING -s ${wg_net}.0/24 -o ${iface} -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -D POSTROUTING -s ${wg_net}.0/24 -o ${iface} -j MASQUERADE
EOF
  )

  echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-pingboost.conf
  sysctl -q -p /etc/sysctl.d/99-pingboost.conf

  cat > "$ENV_FILE" <<EOF
WG_IF=$wg_if
WG_PORT=$wg_port
PING_PORT=$ping_port
WG_NET=$wg_net
ENDPOINT=$endpoint
EOF

  echo "==> Ставлю службу замера пинга (UDP $ping_port)"
  cat > "$ECHO_BIN" <<'PY'
#!/usr/bin/env python3
"""UDP-эхо для замера пинга приложением PingBoost. Возвращает короткий пакет отправителю."""
import socket
import sys

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", int(sys.argv[1])))
while True:
    data, addr = sock.recvfrom(128)
    if len(data) <= 64:  # длинные пакеты не отражаем, чтобы службой нельзя было злоупотребить
        sock.sendto(data, addr)
PY
  chmod 755 "$ECHO_BIN"
  cat > "$ECHO_UNIT" <<EOF
[Unit]
Description=PingBoost UDP echo (замер пинга)
After=network.target

[Service]
ExecStart=$ECHO_BIN $ping_port
Restart=always
DynamicUser=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes

[Install]
WantedBy=multi-user.target
EOF
  chmod 644 "$ECHO_UNIT"

  if [ -f "${BASH_SOURCE[0]}" ]; then install -m 755 "${BASH_SOURCE[0]}" "$SELF_BIN"; fi

  if command -v ufw >/dev/null && ufw status 2>/dev/null | grep "Status: active" >/dev/null; then
    echo "==> ufw активен, открываю порты"
    ufw allow "${wg_port}/udp" >/dev/null
    ufw allow "${ping_port}/udp" >/dev/null
  fi

  echo "==> Запускаю"
  systemctl daemon-reload
  if ! systemctl enable --now "wg-quick@${wg_if}"; then
    journalctl -u "wg-quick@${wg_if}" --no-pager -n 20 || true
    die "WireGuard не запустился. Если VPS на OpenVZ/LXC, там нет модуля ядра WireGuard: нужен KVM-сервер."
  fi
  systemctl enable --now pingboost-echo

  echo
  echo "Готово. В файрволе/панели хостинга должны быть открыты UDP-порты $wg_port и $ping_port."
  echo
  cmd_add_client phone "${SERVER_NAME:-$(hostname)}"
}

cmd_add_client() {
  need_root
  local name="${1:-}" label="${2:-$(hostname)}"
  valid_client_name "$name" || die "имя клиента: латиница, цифры, - и _, до 24 символов. Пример: pingboost add-client phone Германия"
  load_env
  [ ! -e "$CLIENT_DIR/$name.conf" ] || die "клиент $name уже есть. Показать конфиг: pingboost show $name"
  label="${label//[$'\n\r']/ }"

  local conf="$WG_DIR/${WG_IF}.conf" n=2
  while grep -qF "${WG_NET}.${n}/32" "$conf"; do n=$((n + 1)); done
  [ "$n" -le 254 ] || die "в подсети закончились адреса"

  local ckey cpub psk spub
  ckey="$(wg genkey)"
  cpub="$(printf '%s' "$ckey" | wg pubkey)"
  psk="$(wg genpsk)"
  spub="$(cat "$STATE_DIR/server.pub")"

  cat >> "$conf" <<EOF

# client: $name
[Peer]
PublicKey = $cpub
PresharedKey = $psk
AllowedIPs = ${WG_NET}.${n}/32
EOF
  # Добавляем в работающий интерфейс без перезапуска, чтобы не рвать чужие подключения.
  wg set "$WG_IF" peer "$cpub" preshared-key <(printf '%s' "$psk") allowed-ips "${WG_NET}.${n}/32"

  (
    umask 077
    # Только IPv4: игра ходит по IPv4. Если в конфиге разрешить ::/0, а сервер не пересылает IPv6,
    # UDP-пакеты игры по IPv6 будут молча теряться.
    cat > "$CLIENT_DIR/$name.conf" <<EOF
# Name: $label
# PingPort = $PING_PORT
[Interface]
PrivateKey = $ckey
Address = ${WG_NET}.${n}/32
DNS = 1.1.1.1, 1.0.0.1
MTU = 1380

[Peer]
PublicKey = $spub
PresharedKey = $psk
Endpoint = ${ENDPOINT}:${WG_PORT}
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF
  )

  echo "Клиент «$name» добавлен. Скопируй всё между линиями в приложение PingBoost"
  echo "(вкладка «Серверы» → «Добавить сервер» → «Вставить»):"
  echo "----------------------------------------------------------------"
  cat "$CLIENT_DIR/$name.conf"
  echo "----------------------------------------------------------------"
}

cmd_show() {
  need_root
  local name="${1:-}"
  valid_client_name "$name" || die "укажи имя клиента: pingboost show <имя>"
  load_env
  [ -f "$CLIENT_DIR/$name.conf" ] || die "клиента $name нет. Список: pingboost list"
  cat "$CLIENT_DIR/$name.conf"
}

cmd_remove_client() {
  need_root
  local name="${1:-}"
  valid_client_name "$name" || die "укажи имя клиента: pingboost remove-client <имя>"
  load_env
  local file="$CLIENT_DIR/$name.conf" conf="$WG_DIR/${WG_IF}.conf"
  [ -f "$file" ] || die "клиента $name нет. Список: pingboost list"

  local cpub
  cpub="$(awk '$1 == "PrivateKey" { print $3 }' "$file" | wg pubkey)"
  wg set "$WG_IF" peer "$cpub" remove 2>/dev/null || true

  local tmp
  tmp="$(mktemp)"
  awk -v tag="# client: $name" '
    $0 == tag          { skip = 1; next }
    skip && NF == 0    { skip = 0; next }
    !skip              { print }
  ' "$conf" > "$tmp"
  cat "$tmp" > "$conf"
  rm -f "$tmp" "$file"
  echo "Клиент «$name» удалён."
}

cmd_list() {
  load_env
  shopt -s nullglob
  local f found=0
  for f in "$CLIENT_DIR"/*.conf; do
    basename "$f" .conf
    found=1
  done
  [ "$found" -eq 1 ] || echo "(клиентов нет)"
}

cmd_status() {
  load_env
  echo "Сервер: ${ENDPOINT}  WireGuard: ${WG_PORT}/udp  Пинг: ${PING_PORT}/udp"
  systemctl is-active "wg-quick@${WG_IF}" pingboost-echo || true
  wg show "$WG_IF" || true
}

usage() {
  sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

case "${1:-}" in
  install)        shift; cmd_install "$@" ;;
  add-client)     shift; cmd_add_client "$@" ;;
  remove-client)  shift; cmd_remove_client "$@" ;;
  show)           shift; cmd_show "$@" ;;
  list)           shift; cmd_list "$@" ;;
  status)         shift; cmd_status "$@" ;;
  *)              usage ;;
esac
