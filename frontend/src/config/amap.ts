/**
 * 高德 JS API 2.0 的加载器 —— 全站唯一入口。
 *
 * 为什么手写而不用 `@amap/amap-jsapi-loader`：项目原则是「依赖越少越好」（AGENTS.md 第二节），
 * 这段逻辑不过是插一个 <script>，引一个包反而多一处版本兼容风险。
 *
 * 三条容易踩的：
 *   1) 2.0 必须先把 `window._AMapSecurityConfig` 设好再加载脚本，否则控制台报 INVALID_USER_SCODE；
 *      安全密钥和 Key 是两回事，只填 Key 不填密钥的表现是「地图不显示、接口 403」。
 *   2) Key 与安全密钥都来自 `frontend/.env.local`（`VITE_` 前缀，构建时注入），
 *      仓库里只有空模板 `.env.example` —— 真实值不进 Git。
 *   3) 脚本只在进地图页时才加载：3 Mbps 带宽下，不能为了首屏塞几百 KB 的地图 SDK（AGENTS.md 第 16 条）。
 *
 * 至于「Key 会出现在浏览器里」：这是前端地图 Key 的固有属性，靠控制台的域名白名单兜底，
 * 所以它本来就只该放进会被 gitignore 的本地配置，而不是当成真正的机密。
 */

export interface AmapLngLat {
  getLng(): number
  getLat(): number
}

/** 高德各处回传的坐标形态不统一：有的带 getLng()，有的就是 {lng, lat} */
export interface AmapLngLatLike {
  lng?: number
  lat?: number
  getLng?(): number
  getLat?(): number
}

/** 统一读出 {lng, lat}；读不出就返回 null，调用方自己决定忽略还是报错 */
export function readLngLat(value: AmapLngLatLike | null | undefined): { lng: number; lat: number } | null {
  if (!value) {
    return null
  }
  const lng = typeof value.getLng === 'function' ? value.getLng() : value.lng
  const lat = typeof value.getLat === 'function' ? value.getLat() : value.lat
  if (typeof lng !== 'number' || typeof lat !== 'number') {
    return null
  }
  return { lng, lat }
}

export interface AmapBounds {
  /** 点是否在当前视野内 */
  contains(position: [number, number]): boolean
  /** 视野西南角：视野查询算 bbox 用 */
  getSouthWest?(): AmapLngLat
  /** 视野东北角 */
  getNorthEast?(): AmapLngLat
}

/** 地图事件：只有点击用得上坐标，其余事件参数忽略 */
export interface AmapMapEvent {
  lnglat?: AmapLngLatLike
}

export interface AmapMap {
  setCenter(center: [number, number]): void
  setZoom(zoom: number): void
  getZoom?(): number
  getBounds?(): AmapBounds
  on(event: string, handler: (event: AmapMapEvent) => void): void
  setFitView(overlays?: unknown[], immediately?: boolean, avoid?: number[], maxZoom?: number): void
  panTo(position: [number, number]): void
  add(overlay: unknown | unknown[]): void
  remove(overlay: unknown | unknown[]): void
  destroy(): void
  getCenter(): AmapLngLat
  /** 容器像素坐标 → 经纬度，用来「把某个标记挪到面板上方」而不是简单居中 */
  containerToLngLat?(pixel: unknown): AmapLngLat
}

/** 输入提示（AMap.AutoComplete）返回的一条建议 */
export interface AmapTip {
  name?: string
  district?: string
  address?: string
  location?: { lng: number; lat: number } | null
}

export interface AmapAutoComplete {
  search(keyword: string, callback: (status: string, result: { tips?: AmapTip[] }) => void): void
}

/** 逆地理编码（AMap.Geocoder）：坐标 → 文字地址 */
export interface AmapGeocoder {
  getAddress(
    position: [number, number],
    callback: (
      status: string,
      result: {
        regeocode?: {
          formattedAddress?: string
          addressComponent?: {
            province?: string
            city?: string | string[]
            district?: string
          }
        }
      },
    ) => void,
  ): void
}

export interface AmapPoi {
  name?: string
  address?: string
  location?: { lng: number; lat: number } | null
}

export interface AmapPlaceSearch {
  search(
    keyword: string,
    callback: (status: string, result: { poiList?: { pois?: AmapPoi[] } }) => void,
  ): void
}

/**
 * 从逆地理编码的 addressComponent 里取城市名。
 * 直辖市的 city 是空数组，得退回省/区；地图选点与「临时坐标」都靠它定城市。
 */
export function cityOfComponent(component?: {
  province?: string
  city?: string | string[]
  district?: string
}): string {
  if (!component) {
    return ''
  }
  const city = Array.isArray(component.city) ? component.city[0] : component.city
  return city || component.province || component.district || ''
}

export interface AmapOverlay {
  setMap(map: AmapMap | null): void
  setPosition(position: [number, number]): void
  setContent(content: HTMLElement | string): void
  setzIndex(index: number): void
  on(event: string, handler: () => void): void
}

export interface AmapNamespace {
  Map: new (container: HTMLElement, options?: Record<string, unknown>) => AmapMap
  Marker: new (options?: Record<string, unknown>) => AmapOverlay
  CircleMarker: new (options?: Record<string, unknown>) => AmapOverlay
  Pixel: new (x: number, y: number) => unknown
  /** 插件按需加载：不加载就没有 AutoComplete / Geocoder / PlaceSearch */
  plugin?: (names: string | string[], callback: () => void) => void
  AutoComplete?: new (options?: Record<string, unknown>) => AmapAutoComplete
  Geocoder?: new (options?: Record<string, unknown>) => AmapGeocoder
  PlaceSearch?: new (options?: Record<string, unknown>) => AmapPlaceSearch
}

/** 兜底视图中心（武汉，GCJ-02）：既没有定位、也没有任何带坐标的打卡点时才用 */
export const DEFAULT_CENTER: [number, number] = [114.3055, 30.5928]

declare global {
  interface Window {
    /** 高德 2.0 的安全密钥载体，必须在脚本加载前赋值 */
    _AMapSecurityConfig?: { securityJsCode?: string }
    AMap?: AmapNamespace
  }
}

const SCRIPT_ID = 'amap-jsapi'

/** 构建时注入的 Key；空字符串表示本地还没配 .env.local */
export const AMAP_KEY = (import.meta.env.VITE_AMAP_KEY ?? '').trim()

export function amapConfigured(): boolean {
  return AMAP_KEY.length > 0
}

/**
 * 地图样式。PRD 5.7 要求六色自定义底图，但自定义样式必须先在高德控制台建好再拿 ID，
 * 所以这里留了 `VITE_AMAP_STYLE` 口子：没配就退回内置浅色 whitesmoke（不会出现米黄底 + 深灰路）。
 */
export function amapStyle(): string {
  const style = (import.meta.env.VITE_AMAP_STYLE ?? '').trim()
  return style || 'amap://styles/whitesmoke'
}

let loading: Promise<void> | null = null

/** 并发调用共享同一个 Promise：地图页与后续页面都用它，不重复插脚本 */
export function loadAmap(): Promise<void> {
  if (window.AMap) {
    return Promise.resolve()
  }
  if (loading) {
    return loading
  }
  if (!AMAP_KEY) {
    return Promise.reject(
      new Error('缺少 VITE_AMAP_KEY：复制 frontend/.env.example 为 .env.local 并填入高德 Key，然后重启 dev server'),
    )
  }

  const securityCode = (import.meta.env.VITE_AMAP_SECURITY_CODE ?? '').trim()
  if (securityCode) {
    window._AMapSecurityConfig = { securityJsCode: securityCode }
  }

  loading = new Promise<void>((resolve, reject) => {
    const existing = document.getElementById(SCRIPT_ID) as HTMLScriptElement | null
    const script = existing ?? document.createElement('script')
    script.id = SCRIPT_ID
    script.async = true
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(AMAP_KEY)}`
    script.addEventListener('load', () => {
      if (window.AMap) {
        resolve()
      } else {
        reject(new Error('高德脚本已加载但没有挂上 window.AMap'))
      }
    })
    script.addEventListener('error', () => {
      // 允许「重试」：清掉缓存，下次重新插脚本
      loading = null
      script.remove()
      reject(new Error('高德 JS API 加载失败：检查网络、Key 是否有效、域名白名单是否包含当前域名'))
    })
    if (!existing) {
      document.head.appendChild(script)
    }
  })

  return loading
}
