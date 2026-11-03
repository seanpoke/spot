import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发服务器配置。
 *
 * 两条代理缺一不可（AGENTS.md 第 18 条）：
 *   /api      -> 后端接口
 *   /uploads  -> 后端静态图片（本地 CDN_BASE_URL 置空时，前端拿到的是 /uploads/... 相对路径）
 * 漏掉 /uploads 的表现是「接口正常但图片全 404」，很容易误判成后端问题。
 *
 * 前端所有请求都用相对路径，不写死域名：VITE_ 变量是构建时注入的，
 * 写死会导致换域名必须重新构建（第 2 条）。
 */

/**
 * 本地 HTTPS —— **只作用于 `vite dev` / `vite preview`，`vite build` 完全不读**。
 *
 * 为什么需要：手机用局域网 IP 调试时，浏览器要求「安全上下文」（HTTPS 或 localhost），
 * 否则 `navigator.geolocation` 会被直接禁用，表现就是「打开后没有当前坐标」。
 *
 * 这里只在 `certs/dev-key.pem` + `certs/dev-cert.pem` 都存在时才启用，
 * 没有证书就自动退回 HTTP，不影响任何人的默认开发流程，更不影响生产构建。
 *
 * 生成证书（客户端装一次 mkcert 根证书，手机也要装，才不报警告）：
 *   mkcert -install
 *   mkcert -key-file certs/dev-key.pem -cert-file certs/dev-cert.pem localhost 192.168.x.x
 */
const keyPath = fileURLToPath(new URL('./certs/dev-key.pem', import.meta.url))
const certPath = fileURLToPath(new URL('./certs/dev-cert.pem', import.meta.url))

export default defineConfig(({ mode }) => {
  /**
   * 强制走 http：`npm run dev:http`（内部是 `vite --mode http`）。
   *
   * 为什么需要：安卓壳的 `server.url` 是 `http://localhost:5173`，壳里的协议必须和
   * dev server 一致，否则 WebView 直接加载失败。而手机要用 https 访问 dev server，
   * 就得把 mkcert 根证书装进手机「用户凭据」，还得指望 WebView 信任用户 CA —— 多两步、
   * 多一个失败点。
   *
   * 好消息是不必折腾：**`http://localhost` 本身就是「安全上下文」**
   * （页面里 `window.isSecureContext === true`），`navigator.geolocation` 与
   * Service Worker 都正常。所以本地联调统一用 http，Web 与安卓一个地址通吃。
   *
   * 只有「手机用局域网 IP 访问」才真的需要 https —— 那时 origin 不是 localhost，
   * 定位会被浏览器禁掉。这种情况仍走默认的 `npm run dev`。
   */
  const forceHttp = mode === 'http'
  const https =
    !forceHttp && existsSync(keyPath) && existsSync(certPath)
      ? { key: readFileSync(keyPath), cert: readFileSync(certPath) }
      : undefined

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: true,
      port: 5173,
      https,
      proxy: {
        '/api': { target: 'http://localhost:8811', changeOrigin: true },
        '/uploads': { target: 'http://localhost:8811', changeOrigin: true },
      },
    },
    preview: {
      host: true,
      port: 4173,
      https,
    },
  }
})
