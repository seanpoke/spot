#!/usr/bin/env bash
# =============================================================================
# 前端一键发布（在云服务器上执行）：解压 zip → 原地替换 dist → 自检
# -----------------------------------------------------------------------------
#   bash deploy.sh                      # 用 $APP_DIR/visit-dist.zip
#   bash deploy.sh /tmp/v3.zip          # 指定包
#   bash deploy.sh --backend            # 顺带拉镜像重建 spot-api 容器
#   bash deploy.sh --prune              # 结束清 7 天前的旧镜像
#   bash deploy.sh -h                   # 帮助
#
# 目录约定（迁移服务器后改 APP_DIR，或用环境变量覆盖）：
#   $APP_DIR/dist            nginx 挂载出去的前端产物（只清内容，不动目录本身）
#   $APP_DIR/dist.prev       上一版，回滚用
#   $APP_DIR/visit-dist.zip  默认待发布包
#
# 三个必须遵守的点（都踩过）：
#   1. 绝不用 mv dist dist.old && mkdir dist —— docker 的目录 bind mount 按 inode 挂，
#      换掉目录后 nginx 仍端旧内容（新 hash 资源 404、页面还是旧版）。
#      所以只清空 dist 里的内容，dist 目录本身的 inode 不动。
#   2. Windows 打的 zip 可能用反斜杠当分隔符，解到 Linux 会变出
#      "assets\index-xxx.js" 这种文件名，页面直接白屏 —— 下面会修。
#   3. 容器里的 nginx worker 不是 root：dist 要 755、/home/ubuntu 要带 o+x，
#      否则全站 403（Content-Length: 153 的 nginx 默认页），与 dist 内容无关。
#      部署用户 umask=077 时 mkdir 出来的目录是 700，正是这个坑。
# =============================================================================
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/app/visit}"
SITE="${SITE:-visit.sean.ltd}"
CONTAINER="${CONTAINER:-spot-api}"
NGINX_CONTAINER="${NGINX_CONTAINER:-nginx}"
COMPOSE_FILE="${COMPOSE_FILE:-$APP_DIR/deploy/docker-compose.yml}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8811/api/health}"

DIST="$APP_DIR/dist"
PREV="$APP_DIR/dist.prev"
ZIP="$APP_DIR/visit-dist.zip"
BACKEND=0
PRUNE=0
TMP=""

log()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✔\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31m✘ %s\033[0m\n' "$*" >&2; exit 1; }

usage() {
  sed -n '2,/^set -euo/{/^#/p}' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

cleanup() { [ -n "$TMP" ] && [ -d "$TMP" ] && rm -rf "$TMP" || true; }
trap cleanup EXIT

while [ $# -gt 0 ]; do
  case "$1" in
    --backend) BACKEND=1 ;;
    --prune)   PRUNE=1 ;;
    -h|--help) usage ;;
    -*)        die "未知参数：$1（用 -h 看帮助）" ;;
    *)         ZIP="$1" ;;
  esac
  shift
done

# Windows 压缩包的反斜杠路径：把名字里的 \ 还原成目录层级
fix_backslash_paths() {
  local dir="$1" f rel target
  find "$dir" -name '*\\*' -print -quit | grep -q . || return 0
  find "$dir" -depth -name '*\\*' | while IFS= read -r f; do
    rel="${f#"$dir"/}"
    target="$dir/${rel//\\//}"
    mkdir -p "$(dirname "$target")"
    mv "$f" "$target"
  done
}

# ------------------------------------------------------------------ 前端
deploy_frontend() {
  log "前端：解压并替换 $DIST"
  [ -f "$ZIP" ] || die "找不到前端包：$ZIP（先把它传到 $APP_DIR）"
  command -v unzip >/dev/null || die "服务器没有 unzip，先 apt install unzip"

  local rc=0
  TMP="$(mktemp -d "$APP_DIR/.incoming.XXXXXX")"

  # unzip 退出码：0 正常 / 1 有警告（反斜杠这类）/ 2+ 真失败
  unzip -q "$ZIP" -d "$TMP" || rc=$?
  if [ "$rc" -gt 1 ]; then
    die "解压失败（unzip 退出码 $rc），包可能损坏：$ZIP"
  fi
  if [ "$rc" -eq 1 ]; then
    warn "unzip 报了警告（Windows 打包常见），继续检查路径"
  fi
  fix_backslash_paths "$TMP"

  local src="$TMP"
  if [ ! -f "$src/index.html" ]; then
    [ -f "$src/dist/index.html" ] || die "包里没有 index.html，检查是不是传错了文件"
    src="$src/dist"
  fi
  ok "包内入口：$(basename "$ZIP") → index.html"

  # 备份上一版（只备份内容，不动 dist 目录本身）
  rm -rf "$PREV"
  mkdir -p "$PREV"
  if [ -d "$DIST" ]; then
    cp -a "$DIST"/. "$PREV"/ 2>/dev/null || true
  fi

  # 原地替换：清空内容、保留目录（inode 不变，nginx 无需重启）
  mkdir -p "$DIST"
  find "$DIST" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  cp -a "$src"/. "$DIST"/
  ok "dist 已更新（旧版在 $PREV）"

  # 权限归一：cp -a 会保留 zip 里的权限，nginx worker 不是 root，必须可读可进入
  chmod 755 "$DIST"
  find "$DIST" -type d -exec chmod 755 {} +
  find "$DIST" -type f -exec chmod 644 {} +

  # 容器内可读性预检：直接模拟 nginx worker 的视角
  if docker ps --format '{{.Names}}' | grep -qx "$NGINX_CONTAINER"; then
    if ! docker exec "$NGINX_CONTAINER" test -r /var/www/visit/index.html >/dev/null 2>&1; then
      warn "容器内读不到 /var/www/visit/index.html，站点会 403（权限链问题）："
      warn "  sudo chmod o+x /home/ubuntu && sudo chmod 755 $DIST"
    fi
  else
    warn "没找到运行中的 nginx 容器（$NGINX_CONTAINER），跳过容器内预检"
  fi

  # 自检：本地 index.html 的入口 hash 必须与 nginx 实际端出的一致
  local want="" got="" code="" resp="" attempt
  want="$(grep -o 'assets/index-[A-Za-z0-9_-]*\.js' "$DIST/index.html" | head -1)"
  [ -n "$want" ] || die "dist/index.html 里找不到入口 JS，包可能不完整"
  ok "本地入口：$want"

  for attempt in 1 2 3; do
    resp="$(curl -sS --max-time 8 --resolve "$SITE:443:127.0.0.1" \
            -w '\n%{http_code}' "https://$SITE/" 2>/dev/null || true)"
    code="$(printf '%s\n' "$resp" | tail -n1)"
    got="$(printf '%s\n' "$resp" | grep -o 'assets/index-[A-Za-z0-9_-]*\.js' | head -1 || true)"

    if [ "$code" = "200" ] && [ "$got" = "$want" ]; then
      break
    fi

    case "$code" in
      000|"")
        warn "本机 127.0.0.1:443 连不上，改用公网地址自检…"
        resp="$(curl -sS --max-time 8 -w '\n%{http_code}' "https://$SITE/" 2>/dev/null || true)"
        code="$(printf '%s\n' "$resp" | tail -n1)"
        got="$(printf '%s\n' "$resp" | grep -o 'assets/index-[A-Za-z0-9_-]*\.js' | head -1 || true)"
        [ "$code" = "200" ] && [ "$got" = "$want" ] && break
        warn "公网也不通：HTTP ${code:-无响应}"
        ;;
      403)
        warn "HTTP 403：容器内读不到文件（权限链，不是 dist 内容）："
        warn "  namei -l $DIST/index.html    # /home/ubuntu 与 dist 都要 o+r/x"
        ;;
      *)
        warn "HTTP $code，端出的 hash=${got:-无}（期望 $want）"
        ;;
    esac

    if [ "$attempt" = 1 ] && [ "$code" != "200" ]; then
      warn "重启 nginx 再试一次…"
      docker restart "$NGINX_CONTAINER" >/dev/null 2>&1 || true
      sleep 3
    fi
  done

  if [ "$code" = "200" ] && [ "$got" = "$want" ]; then
    ok "nginx 已在端新版本：$got"
  else
    warn "自检未通过：HTTP ${code:-无响应}，端出的入口 hash=${got:-无}"
    warn "  本地入口：$want"
    warn "  挂载：docker inspect $NGINX_CONTAINER --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{println}}{{end}}' | grep visit"
    warn "  dist 文件已就位，浏览器 Ctrl+F5 可能已经是新版，先人工看一眼再排查"
  fi
}

# ------------------------------------------------------------------ 后端
deploy_backend() {
  log "后端：拉镜像并重建容器"
  [ -f "$COMPOSE_FILE" ] || die "找不到 compose 文件：$COMPOSE_FILE（用 COMPOSE_FILE=... 指定）"
  docker compose -f "$COMPOSE_FILE" pull
  docker compose -f "$COMPOSE_FILE" up -d

  log "等待容器 healthy（最多 90s）"
  local status="" i
  for i in $(seq 1 45); do
    status="$(docker inspect "$CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null || echo missing)"
    [ "$status" = "healthy" ] && break
    sleep 2
  done
  if [ "$status" = "healthy" ]; then
    ok "容器 healthy"
  else
    warn "容器状态：$status —— 看日志：docker logs --tail 60 $CONTAINER"
  fi

  if curl -fsS --max-time 8 "$HEALTH_URL" >/dev/null 2>&1; then
    ok "健康检查通过：$(curl -s --max-time 8 "$HEALTH_URL")"
  else
    die "健康检查失败：$HEALTH_URL 取不到 200，看 docker logs --tail 60 $CONTAINER"
  fi
  docker compose -f "$COMPOSE_FILE" ps
}

# ------------------------------------------------------------------ 收尾
deploy_frontend
if [ "$BACKEND" = 1 ]; then deploy_backend; fi

if [ "$PRUNE" = 1 ]; then
  log "清理 7 天前的未被引用镜像"
  docker image prune -f --filter until=168h | tail -2
fi

log "完成"
printf '  站点：https://%s\n' "$SITE"
printf '  回滚前端：cd %s && find dist -mindepth 1 -delete && cp -a dist.prev/. dist/\n' "$APP_DIR"
printf '  换服务器：APP_DIR=/新路径 bash deploy.sh\n'
