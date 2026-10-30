/**
 * 请求封装 —— 全站唯一的出网入口。
 *
 * 约定：
 *   1) 一律走相对路径 /api/...，不写死域名（AGENTS.md 第 2 条）
 *      —— 换域名不用重新构建，dev / 线上都是同一个路径
 *   2) 统一解析后端 {code,msg,data}：code !== 0 或 HTTP 非 2xx 一律抛 HttpError
 *   3) 图片地址也由后端返回完整字符串，前端不做任何拼接（第 17 条）
 *
 * 不用 axios：fetch 够用，少一个依赖就少一个坑（技术选型核心原则：依赖越少越好）。
 */

export interface ApiResult<T> {
  code: number
  msg: string
  data: T | null
}

export class HttpError extends Error {
  /** HTTP 状态码；0 表示请求没发出去（后端没起或断网） */
  readonly status: number
  /** 失败响应里可能带诊断信息（如 /health 的 503 仍带各依赖状态） */
  readonly data: unknown

  constructor(status: number, message: string, data?: unknown) {
    super(message)
    this.name = 'HttpError'
    this.status = status
    this.data = data
  }
}

const API_BASE = '/api'

/**
 * 401 的统一出口。由 main.ts 注册成「清会话缓存 + 跳口令门」，
 * 这样会话在页面打开期间过期时，用户是被送回口令门，而不是看到一个空页面。
 *
 * 用回调而不是在本文件里直接 import router：http 是最底层，反向依赖会造成循环引用。
 */
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  // FormData 必须让浏览器自己写 Content-Type（里面带 multipart boundary），
  // 这里强设成 application/json 会让上传直接 400
  if (init.body !== undefined && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      // 会话是 HttpOnly Cookie，同源请求必须带上
      credentials: 'same-origin',
      ...init,
      headers,
    })
  } catch (error) {
    // 主动取消（地图拖动太快时放弃旧请求）不是网络故障，原样抛出去让调用方自己判断，
    // 别包装成「连不上后端」去污染错误提示
    if ((error as { name?: string } | null)?.name === 'AbortError') {
      throw error
    }
    throw new HttpError(0, '连不上后端，请确认服务已启动')
  }

  let body: ApiResult<T> | null = null
  try {
    body = (await response.json()) as ApiResult<T>
  } catch {
    // 502 / 504 之类由 Nginx 直接返回的 HTML，没有 JSON body
  }

  if (!response.ok) {
    if (response.status === 401) {
      // 会话过期 / 口令被改：先让上层清掉本地记忆再跳转，避免守卫用旧缓存放行
      unauthorizedHandler?.()
    }
    throw new HttpError(
      response.status,
      body?.msg ?? `请求失败（HTTP ${response.status}）`,
      body?.data,
    )
  }
  if (body && body.code !== 0) {
    throw new HttpError(body.code, body.msg, body.data)
  }

  return (body?.data ?? null) as T
}

function jsonBody(payload: unknown): string | undefined {
  return payload === undefined ? undefined : JSON.stringify(payload)
}

export const http = {
  // init 用来传 AbortSignal：地图视野查询会被下一次拖动取消
  get: <T>(path: string, init?: RequestInit) => request<T>(path, init),
  post: <T>(path: string, payload?: unknown) =>
    request<T>(path, { method: 'POST', body: jsonBody(payload) }),
  put: <T>(path: string, payload?: unknown) =>
    request<T>(path, { method: 'PUT', body: jsonBody(payload) }),
  patch: <T>(path: string, payload?: unknown) =>
    request<T>(path, { method: 'PATCH', body: jsonBody(payload) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
