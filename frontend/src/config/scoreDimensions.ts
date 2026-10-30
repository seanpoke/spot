/**
 * 分项评分定义 —— 全站唯一来源（PRD 4.5）。
 *
 * 后端只把这个结构当 JSON 存进 visit.scores，不关心有几项、叫什么
 * （AGENTS.md 第 8 条）。要加第五项，只改本文件，不用动表、不用动 Java。
 *
 * 存储格式：
 *   [{ "key": "env", "label": "环境", "value": 4 }, ...]
 * value 允许为 null —— 表示这一项不适用（餐厅没有「技术」可评）。
 * 计算均分时<b>必须跳过 null</b>，不要当 0 分拉低结果。
 */

export type ScoreKey = 'env' | 'service' | 'tech' | 'value'

export interface ScoreDimension {
  key: ScoreKey
  label: string
  /** 录入页的辅助说明，写清楚评的是什么，避免随手打分 */
  hint: string
}

export const SCORE_MAX = 5

export const SCORE_DIMENSIONS: readonly ScoreDimension[] = [
  { key: 'env', label: '环境', hint: '场地条件、氛围、整洁度' },
  { key: 'service', label: '服务', hint: '态度、响应、流程' },
  { key: 'tech', label: '技术', hint: '手法、专业度、出品质量' },
  { key: 'value', label: '性价比', hint: '价格与体验的匹配度' },
]

/** 存库用的分项结构（与 db/schema.sql 里 visit.scores 的示例一致） */
export interface ScoreItem {
  key: ScoreKey | string
  label: string
  value: number | null
}

/** 把 5 分制分项换算成均值，跳过 null；全空返回 null */
export function averageScore(items: readonly ScoreItem[]): number | null {
  const values = items.map((item) => item.value).filter((value): value is number => typeof value === 'number')
  if (values.length === 0) {
    return null
  }
  return values.reduce((sum, value) => sum + value, 0) / values.length
}
