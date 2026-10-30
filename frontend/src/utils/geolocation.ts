/**
 * 获取当前位置（带降级重试）。
 *
 * 背景：安卓上 `enableHighAccuracy: false` 走的是网络 / Wi-Fi 定位源，
 * 只开了 GPS、没开网络定位的机器会直接抛 POSITION_UNAVAILABLE；
 * 这时换成 `enableHighAccuracy: true`（强制 GPS）往往能拿到坐标。
 *
 * 只在「位置不可用(2) / 超时(3)」时重试；权限被拒(1)重试也没有意义。
 */
export interface GeoOptions {
  /** 跳过网络定位源，直接走高精度 GPS。给「上一次网络定位已判不可用」的重试用 */
  gpsOnly?: boolean
}

export function getPositionWithFallback(options: GeoOptions = {}): Promise<GeolocationPosition> {
  const attempt = (highAccuracy: boolean): Promise<GeolocationPosition> =>
    new Promise((resolve, reject) => {
      navigator.geolocation.getCurrentPosition(resolve, reject, {
        enableHighAccuracy: highAccuracy,
        timeout: highAccuracy ? 15000 : 8000,
        maximumAge: highAccuracy ? 0 : 60_000,
      })
    })

  if (options.gpsOnly) {
    return attempt(true)
  }

  return attempt(false).catch((error: GeolocationPositionError) => {
    if (error.code === 1) {
      throw error
    }
    return attempt(true)
  })
}
