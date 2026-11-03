import type { CapacitorConfig } from '@capacitor/cli'

/**
 * Android 壳的配置。
 *
 * **默认 A 模式（壳直接加载线上站点），本地调试用环境变量覆盖。**
 * 为什么要能覆盖：A 模式下 APK 只是个远程壳，装多少次都是服务器上那份文件；
 * 调本机代码（尤其是排查老 WebView 的渲染问题）时必须让壳真的跑本地那份。
 *
 *   # 默认：远程壳，指向线上（CI 出包用的就是这个）
 *   npx cap sync android
 *
 *   # 本地调试：壳加载 PC 上的 dev server（Web 与安卓共用一个地址）
 *   $env:CAP_SERVER_URL='http://localhost:5173'; npx cap sync android
 *
 *   # 离线壳：打包内置 dist，不连任何服务器
 *   $env:CAP_SERVER_URL=''; npx cap sync android
 *
 * 本地调试那套还需要：
 *   1) PC 上 `npm run dev:http`（必须是 http，协议要和 server.url 一致）
 *   2) USB 连着手机后 `adb reverse tcp:5173 tcp:5173`
 *   3) http://localhost 是安全上下文，定位可用，不用给手机装证书
 *
 * 代价：本地模式下拔线或关掉 dev server，App 立刻白屏 —— 所以它只用于调试。
 */
const DEFAULT_SERVER_URL = 'https://visit.sean.ltd'

/** 没设环境变量 → 线上；设了空字符串 → 不配 server（打包内置 dist） */
const serverUrl =
  process.env.CAP_SERVER_URL === undefined ? DEFAULT_SERVER_URL : process.env.CAP_SERVER_URL.trim()

const config: CapacitorConfig = {
  appId: 'ltd.sean.visit',
  appName: '打卡',
  webDir: 'dist',
  android: {
    allowMixedContent: false,
  },
}

if (serverUrl) {
  config.server = {
    url: serverUrl,
    // 明文只对 http 放开；线上 https 不需要，装了也不会有副作用
    cleartext: serverUrl.startsWith('http://'),
  }
}

export default config
