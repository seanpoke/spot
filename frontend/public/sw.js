/**
 * Service Worker —— 只为「加到主屏幕」和断网时的壳兜底，不做全站离线。
 *
 * 三条边界，改动前先看清楚：
 *   1) /api/** 与 /uploads/** 一律不接管：会话是 HttpOnly Cookie，缓存接口响应会串会话；
 *      图片走 CDN 或后端静态，交给浏览器缓存更省事。
 *   2) 页面导航用「网络优先」：改完前端重新发布，用户下次打开拿到的就是新版，
 *      只有断网时才回落到缓存的 index。
 *   3) /assets/** 是带 hash 的构建产物，内容永不变，用「缓存优先」。
 *
 * 缓存名带版本号：发布不兼容改动时改 CACHE 值，旧缓存会在 activate 里被清掉。
 */
const CACHE = 'visit-static-v1'
const PRECACHE = [
  '/',
  '/manifest.webmanifest',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/apple-touch-icon.png',
]

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(PRECACHE))
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return

  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/uploads/')) return

  // 页面导航：网络优先，断网回落到缓存的首页
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request).catch(() => caches.match('/').then((hit) => hit || Response.error())),
    )
    return
  }

  // 带 hash 的静态产物：缓存优先，未命中则回源并写入缓存
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(
      caches.match(request).then((hit) => {
        if (hit) return hit
        return fetch(request).then((response) => {
          if (response.ok) {
            const copy = response.clone()
            caches.open(CACHE).then((cache) => cache.put(request, copy))
          }
          return response
        })
      }),
    )
  }
})
