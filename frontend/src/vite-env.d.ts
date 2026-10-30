/// <reference types="vite/client" />

/**
 * 前端可见的环境变量（构建时注入，会打进 dist，天然公开）。
 * 只能放「公开也没关系」的值 —— 高德 JS Key 就是这种：它靠 referer 白名单保护。
 * 后端口令、数据库密码一律不在此列。
 */
interface ImportMetaEnv {
  /** 高德 JS API Key，必填，见 frontend/.env.local */
  readonly VITE_AMAP_KEY?: string
  /** 高德安全密钥，必填；漏配时本地能跑、线上白屏 */
  readonly VITE_AMAP_SECURITY_CODE?: string
  /** 地图样式 ID，可选；留空走内置 whitesmoke，填 amap://styles/xxx 用控制台自定义样式 */
  readonly VITE_AMAP_STYLE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
