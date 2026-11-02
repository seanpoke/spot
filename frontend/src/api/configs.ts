import { http } from './http'

/**
 * 基础配置（业务字典）接口。
 *
 * 配置存在后端 `base_config` 表里，一组配置用一个业务编码（code）区分，
 * 比如 `priceItem` 是消费项目的预设。配置由 SQL 维护，前端只读。
 */
export interface ConfigItem {
  /** 配置项标识，如消费项目的 `1P` */
  key: string
  /** 配置值，统一是字符串；priceItem 里是默认价格（0 = 不预填） */
  value: string
  /** 说明，如「一次」；只用来展示 */
  desc: string
}

/** 按业务编码取一组配置，顺序就是库里的自增 id 顺序 */
export function listConfigs(code: string): Promise<ConfigItem[]> {
  return http.get<ConfigItem[]>(`/configs?code=${encodeURIComponent(code)}`)
}
