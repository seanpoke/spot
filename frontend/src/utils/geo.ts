/**
 * 坐标小工具。地图标记、面板列表、详情页都要显示「240m / 1.2km」，算法只留一份。
 *
 * 坐标系：全项目统一 GCJ-02（高德），不做 WGS-84 转换 —— 所有坐标都从高德或手填同一体系来，
 * 混入 GPS 原始坐标会整体偏移几百米，那是比「没有距离」更糟的错误。
 */

export interface LatLng {
  lat: number
  lng: number
}

const EARTH_RADIUS = 6371_000

const PI = Math.PI
/** 克拉索夫斯基椭球参数（WGS-84 → GCJ-02 偏移算法固定用这两个） */
const GCJ_A = 6378245.0
const GCJ_EE = 0.00669342162296594323

function outOfChina(lat: number, lng: number): boolean {
  return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
}

function transformLat(x: number, y: number): number {
  let ret = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  ret += ((20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2) / 3
  ret += ((20 * Math.sin(y * PI) + 40 * Math.sin((y / 3) * PI)) * 2) / 3
  ret += ((160 * Math.sin((y / 12) * PI) + 320 * Math.sin((y * PI) / 30)) * 2) / 3
  return ret
}

function transformLng(x: number, y: number): number {
  let ret = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  ret += ((20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2) / 3
  ret += ((20 * Math.sin(x * PI) + 40 * Math.sin((x / 3) * PI)) * 2) / 3
  ret += ((150 * Math.sin((x / 12) * PI) + 300 * Math.sin((x / 30) * PI)) * 2) / 3
  return ret
}

/**
 * WGS-84 → GCJ-02（火星坐标）。
 *
 * 为什么必须有这一步：浏览器 `navigator.geolocation` 给的是 WGS-84，
 * 而项目里所有坐标（高德底图、手填的打卡点）都是 GCJ-02。
 * 直接混用会在国内产生 300~500m 的系统性偏移 —— 表现为「我明明站在店门口，蓝点却在对街」。
 */
export function wgs84ToGcj02(lat: number, lng: number): LatLng {
  if (outOfChina(lat, lng)) {
    return { lat, lng }
  }
  let dLat = transformLat(lng - 105, lat - 35)
  let dLng = transformLng(lng - 105, lat - 35)
  const radLat = (lat / 180) * PI
  let magic = Math.sin(radLat)
  magic = 1 - GCJ_EE * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  dLat = (dLat * 180) / (((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic)) * PI)
  dLng = (dLng * 180) / ((GCJ_A / sqrtMagic) * Math.cos(radLat) * PI)
  return { lat: lat + dLat, lng: lng + dLng }
}

/** 两点球面距离，单位米 */
export function distanceMeters(from: LatLng, to: LatLng): number {
  const toRad = (deg: number): number => (deg * Math.PI) / 180
  const dLat = toRad(to.lat - from.lat)
  const dLng = toRad(to.lng - from.lng)
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(from.lat)) * Math.cos(toRad(to.lat)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
  return 2 * EARTH_RADIUS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/**
 * 距离文案：<1km 取整到 10m，≥1km 保留一位小数。
 * 不显示 0m —— 「就在脚下」用 10m 起步，0 会被读成「没有距离信息」。
 */
export function formatDistance(meters: number): string {
  if (!Number.isFinite(meters) || meters < 0) {
    return ''
  }
  if (meters < 1000) {
    const rounded = Math.max(10, Math.round(meters / 10) * 10)
    return `${rounded}m`
  }
  return `${(meters / 1000).toFixed(1)}km`
}
