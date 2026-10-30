import { http } from './http'

/**
 * 后端环境自检结果。脚手架阶段用来确认两条连接都通，
 * 业务接口齐了之后这个页面可以留着当「服务异常时的手动探活」。
 */
export interface HealthReport {
  healthy: boolean
  /** up / down */
  mysql: string
  /** up / down */
  redis: string
  /** 当前连接的库名，正常应是 visit_db */
  database: string | null
}

/** 依赖不通时后端返回 503，http 层会抛 HttpError，页面据此显示不可用 */
export function getHealth(): Promise<HealthReport> {
  return http.get<HealthReport>('/health')
}
