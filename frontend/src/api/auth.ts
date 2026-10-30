import { http } from './http'

/**
 * 口令门接口。
 *
 * token 只在 HttpOnly Cookie 里，JS 读不到也拿不到 —— 所以这里没有任何
 * 「保存 token」的代码，也不要有。前端只记住「当前是否已通过」这一个布尔值，
 * 真实校验永远在服务端。
 */

export interface SessionInfo {
  authenticated: boolean
  /** 距过期秒数（每次请求都会续期） */
  expiresInSeconds: number
}

export interface LoginResult {
  expiresInDays: number
}

/** 已登录 / 未登录的本地记忆；null = 还没问过后端 */
let authenticated: boolean | null = null

export function login(password: string): Promise<LoginResult> {
  return http.post<LoginResult>('/auth/login', { password })
}

export function logout(): Promise<void> {
  return http.post<void>('/auth/logout')
}

export function me(): Promise<SessionInfo> {
  return http.get<SessionInfo>('/auth/me')
}

/**
 * 路由守卫的判断依据。只在第一次真正打接口，之后用本地记忆，
 * 否则每次切页面都多一次请求（服务器带宽小，能省就省）。
 */
export async function ensureSession(): Promise<boolean> {
  if (authenticated !== null) {
    return authenticated
  }
  try {
    await me()
    authenticated = true
  } catch {
    // 401 / 503 都算「进不去」：503（Redis 挂）时后端本来就不允许放行
    authenticated = false
  }
  return authenticated
}

export function markAuthenticated(): void {
  authenticated = true
}

export function markAnonymous(): void {
  authenticated = false
}
