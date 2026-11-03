import { Capacitor } from '@capacitor/core'

/**
 * 平台差异的唯一出口。
 *
 * 为什么需要这一层：同一份 Vue 代码要跑在浏览器和 Capacitor 的 WebView 里，
 * 但这三件事两边行为不同，散落在页面里判断平台一定会漏：
 *
 *   复制     浏览器 navigator.clipboard（需要安全上下文） / 原生 Clipboard 插件
 *   打开外链 浏览器 window.location.href 直接发 scheme /
 *            原生必须走 AppLauncher —— WebView 会把 weixin:// 这类导航当成非法地址拦掉
 *   返回键   浏览器没有 / 原生要接管，否则一按就退出 App
 *
 * 插件都用动态 import：Web 构建里 `isNative` 为 false，这几个包不会进主 chunk。
 */
export const isNative = Capacitor.isNativePlatform()

/** 复制文本；失败由调用方提示，这里不吞错 */
export async function copyText(text: string): Promise<void> {
  if (isNative) {
    const { Clipboard } = await import('@capacitor/clipboard')
    await Clipboard.write({ string: text })
    return
  }
  await navigator.clipboard.writeText(text)
}

/**
 * 打开外部 App 或拨号（weixin:// / mqq:// / tg:// / yuni:// / tel:）。
 *
 * 注意顺序：调用方要先发这个、再 await 复制。iOS Safari 会把「await 之后」
 * 算作失去用户手势，那样 scheme 会被拦掉。
 */
export async function openExternal(url: string): Promise<void> {
  if (isNative) {
    const { AppLauncher } = await import('@capacitor/app-launcher')
    await AppLauncher.openUrl({ url })
    return
  }
  window.location.href = url
}

/**
 * 接管安卓物理返回键。handler 返回 true 表示"我处理了"，返回 false 则退出 App。
 * 返回一个解除监听的函数；Web 上是空操作。
 */
export async function onBackPress(handler: () => boolean): Promise<() => void> {
  if (!isNative) {
    return () => undefined
  }
  const { App } = await import('@capacitor/app')
  const listener = await App.addListener('backButton', () => {
    if (!handler()) {
      void App.exitApp()
    }
  })
  return () => void listener.remove()
}
