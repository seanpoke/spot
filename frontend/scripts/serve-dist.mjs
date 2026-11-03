/**
 * 本地站点：把当前的 dist 当静态站点跑起来，并把 /api、/uploads 反代到线上后端。
 *
 * 为什么需要它：安卓壳现在是「加载本地」模式（`capacitor.config.ts` 里
 * `server.url = http://localhost:5173`），`npx cap sync` 只负责把配置塞进 APK，
 * 页面本身要靠一个本地 HTTP 服务提供。dist 里所有请求都是相对路径，没有这个
 * 反代，登录接口会 404，连口令门都过不去。
 *
 * 用法（在 frontend 目录下）：
 *   npm run build                 # 改了前端之后
 *   node scripts/serve-dist.mjs   # 起服务，默认 http://localhost:5173
 *   adb reverse tcp:5173 tcp:5173 # USB 连着手机时，把手机的 5173 打到这台电脑
 *   打开 App
 *
 * 参数：
 *   --port 5173                     监听端口
 *   --dist dist                     静态目录（相对 frontend 或绝对路径）
 *   --target https://visit.sean.ltd 反代目标（默认线上，改了后端域名时再动）
 *
 * 注意：它是开发工具，不上生产。拔掉 USB 或关掉这个进程，App 会白屏 ——
 * 这正是「加载本地」的代价，切回线上稳定版见 capacitor.config.ts 里的说明。
 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.resolve(here, '..')

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}

const DIST = path.resolve(frontendRoot, arg('dist', 'dist'))
const PORT = Number(arg('port', '5173'))
const UPSTREAM = arg('target', 'https://visit.sean.ltd').replace(/\/$/, '')

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
}

/**
 * 线上返回的 Cookie 是给 https://visit.sean.ltd 的，直接透传会存不住：
 * 带 Domain 的会被浏览器丢掉，带 Secure 的在 http://localhost 下也会被丢掉。
 * 这里统一剥掉 Domain / Secure，SameSite=None 降级成 Lax（None 强制要求 Secure）。
 */
function rewriteCookie(raw) {
  return raw
    .split(/,(?=[^;]+?=)/)
    .map((one) =>
      one
        .replace(/;\s*Secure/gi, '')
        .replace(/;\s*Domain=[^;]*/gi, '')
        .replace(/;\s*SameSite=None/gi, '; SameSite=Lax'),
    )
    .join(',')
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = []
    req.on('data', (chunk) => chunks.push(chunk))
    req.on('end', () => resolve(Buffer.concat(chunks)))
  })
}

async function proxy(req, res) {
  const headers = { ...req.headers }
  delete headers.host
  delete headers.connection
  const body = req.method === 'GET' || req.method === 'HEAD' ? undefined : await readBody(req)

  try {
    const upstream = await fetch(UPSTREAM + req.url, {
      method: req.method,
      headers,
      body,
      redirect: 'manual',
    })
    const out = {}
    upstream.headers.forEach((value, key) => {
      // 长度/编码由 Node 重算，set-cookie 单独处理（可能是多条）
      if (key === 'set-cookie' || key === 'content-encoding' || key === 'content-length') return
      out[key] = value
    })
    const cookies = upstream.headers.getSetCookie?.() ?? []
    if (cookies.length) out['set-cookie'] = cookies.map(rewriteCookie)

    const buf = Buffer.from(await upstream.arrayBuffer())
    res.writeHead(upstream.status, out)
    res.end(buf)
  } catch (error) {
    res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' })
    res.end(`proxy failed: ${error.message}`)
  }
}

function serveStatic(req, res) {
  const url = new URL(req.url, 'http://localhost')
  let rel = decodeURIComponent(url.pathname)
  if (rel.endsWith('/')) rel += 'index.html'
  let file = path.join(DIST, rel)
  if (!file.startsWith(DIST)) {
    res.writeHead(403)
    res.end()
    return
  }
  // SPA：找不到就回 index.html（前端路由由 vue-router 接管）
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    file = path.join(DIST, 'index.html')
  }
  const ext = path.extname(file).toLowerCase()
  res.writeHead(200, {
    'content-type': MIME[ext] || 'application/octet-stream',
    // 本地调试不缓存，省得「改了没反应」还要手动清
    'cache-control': 'no-store',
  })
  res.end(fs.readFileSync(file))
}

if (!fs.existsSync(path.join(DIST, 'index.html'))) {
  console.error(`[serve-dist] ${DIST} 里没有 index.html，先跑 npm run build`)
  process.exit(1)
}

http
  .createServer((req, res) => {
    if (req.url.startsWith('/api/') || req.url.startsWith('/uploads/')) {
      void proxy(req, res)
      return
    }
    serveStatic(req, res)
  })
  .listen(PORT, '0.0.0.0', () => {
    console.log(`[serve-dist] ${DIST}`)
    console.log(`[serve-dist] http://localhost:${PORT}  （/api /uploads -> ${UPSTREAM}）`)
    console.log('[serve-dist] 手机端记得先 adb reverse tcp:%d tcp:%d', PORT, PORT)
  })
