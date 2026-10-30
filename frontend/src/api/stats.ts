import { http } from './http'

/**
 * 统计看板接口。
 *
 * 段位**名称与配色不在响应里**：后端只知道 `rank_level` 是 0~6，
 * 名字和颜色一律从 `config/ranks.ts` 取（AGENTS.md 第 7 条）。
 */

/** 某段位下的打卡点数（后端固定返回 7 条，level 0~6） */
export interface RankBucket {
  level: number
  count: number
}

/** 某月的到访次数（后端固定返回 12 条，month 1~12） */
export interface MonthBucket {
  month: number
  count: number
}

export interface TopSpotItem {
  id: string
  name: string
  /** 已拼好前缀的完整 URL，直接塞 <img :src> */
  avatar?: string
  visitCount: number
  /** yyyy-MM-dd；理论上一定有值（有到访才会上榜） */
  lastVisitAt?: string
}

export interface StatsResponse {
  spotCount: number
  visitCount: number
  cityCount: number
  /** 每月到访统计的年份 */
  year: number
  ranks: RankBucket[]
  monthly: MonthBucket[]
  topSpots: TopSpotItem[]
}

export function getStats(year?: number): Promise<StatsResponse> {
  return http.get<StatsResponse>(year === undefined ? '/stats' : `/stats?year=${year}`)
}
