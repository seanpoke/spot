import { http } from './http'
import type { PhotoRef } from './spots'

/**
 * 到访记录接口封装。
 *
 * 分项评分结构只在前端定义（`config/scoreDimensions.ts`），后端原样存进
 * `visit.scores` 这个 JSON 列，不校验有几项、叫什么（AGENTS.md 第 8 条）。
 * 照片不存 visit 表，统一在 photo 表（owner_type='visit'），只在查详情时返回。
 */

/** 一项分项评分：value 为 null 表示这一项不适用，算均分时必须跳过 */
export interface ScoreItem {
  key: string
  label: string
  value: number | null
}

export interface VisitItem {
  id: string
  /** 所属打卡点，编辑页据此知道「改完回哪个详情」 */
  spotId: string
  /** yyyy-MM-dd，只到天 */
  visitedAt: string
  /** 整体星级 1~5 */
  rating: number
  /** 没评时是空数组，不是 null */
  scores?: ScoreItem[]
  cost?: number
  companions?: string
  note?: string
  /** 照片（查详情时才返回，列表/时间线没有），URL 已是完整地址 */
  photos?: PhotoRef[]
}

/** 整体提交：null 就是清空，不是「没传即不改」 */
export interface VisitSaveRequest {
  visitedAt: string
  rating: number
  scores?: ScoreItem[]
  cost?: number | null
  companions?: string | null
  note?: string | null
  /** 这条到访的照片（最多 9 张）；后端存 photo 表，空数组即清空 */
  photos?: PhotoRef[]
  /**
   * 这次给的段位。传了才算「手动定级」，后端会回写 spot.rank_level 并锁定；
   * 传 null 表示「不表态」，由后端在未锁定时按新均分更新建议值。
   */
  rankLevel?: number | null
}

/** 狂人日志的一行：一次到访 + 所属打卡点的最少信息（不带标签，PRD 5.2） */
export interface TimelineItem {
  id: string
  spotId: string
  /** 打卡点被删时到访会级联删掉，这里理论上一定有值 */
  spotName?: string
  /** 已拼好前缀的完整 URL，直接塞 <img :src> */
  spotAvatar?: string
  visitedAt: string
  rating: number
  cost?: number
  companions?: string
  note?: string
}

export interface TimelineSummary {
  total: number
  monthCount: number
  /** 一条评分都没有时后端不返回（Jackson 跳过 null），所以是可选的 */
  avgRating?: number
}

export interface TimelineResponse {
  summary: TimelineSummary
  items: TimelineItem[]
}

export function listVisits(spotId: string): Promise<VisitItem[]> {
  return http.get<VisitItem[]>(`/spots/${spotId}/visits`)
}

/** 狂人日志：全量到访（最新在前）+ 顶部三个数字 */
export function getTimeline(): Promise<TimelineResponse> {
  return http.get<TimelineResponse>('/visits/timeline')
}

export function getVisit(id: string): Promise<VisitItem> {
  return http.get<VisitItem>(`/visits/${id}`)
}

export function createVisit(spotId: string, payload: VisitSaveRequest): Promise<{ id: string }> {
  return http.post<{ id: string }>(`/spots/${spotId}/visits`, payload)
}

export function updateVisit(id: string, payload: VisitSaveRequest): Promise<void> {
  return http.put<void>(`/visits/${id}`, payload)
}

export function deleteVisit(id: string): Promise<void> {
  return http.delete<void>(`/visits/${id}`)
}
