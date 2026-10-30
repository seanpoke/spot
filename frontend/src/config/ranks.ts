/**
 * 段位定义与配色 —— 全站唯一来源。
 *
 * 任何地方不得硬编码段位名称或色值（AGENTS.md 第 7、22 条）。改动顺序：
 * PRD 5.2 配色表 -> 本文件 -> 原型（designs/打卡地图/扫街榜原型.html）-> 重出截图。
 *
 * 段位只走「7 段刻度条」：不用色块、不用数字角标（会被读成「去过几次」）、
 * 不用汉字首字。唯一例外是编辑页的段位阶梯选择器。
 */

export interface Rank {
  /** 0~6，与 spot.rank_level 一致 */
  level: number
  /** 完整名，详情页与选择器用 */
  name: string
  /** 单字，只给编辑页的 7 格阶梯选择器用 */
  short: string
  /** 唯一允许的段位色来源 */
  color: string
  /** 语义：这是「我对这个点的推荐等级」，不是别人的评价 */
  meaning: string
}

export const RANKS: readonly Rank[] = [
  { level: 0, name: '倔强青铜', short: '青', color: '#7a5c42', meaning: '翻车，别去' },
  { level: 1, name: '秩序白银', short: '银', color: '#767e86', meaning: '有点失望' },
  { level: 2, name: '荣耀黄金', short: '金', color: '#a87a1e', meaning: '一般' },
  { level: 3, name: '尊贵铂金', short: '铂', color: '#2e8a7a', meaning: '还行' },
  { level: 4, name: '永恒钻石', short: '钻', color: '#2a6fa8', meaning: '不错' },
  { level: 5, name: '至尊星耀', short: '星', color: '#7a4fb8', meaning: '很值得' },
  { level: 6, name: '最强王者', short: '王', color: '#c2452b', meaning: '闭眼推荐' },
]

export const RANK_LEVEL_MAX = RANKS.length - 1

/**
 * 刻度条三套尺寸，不能混用。
 * mini / large 是固定像素；fluid 宽度跟容器走（详情页概览卡的通栏条）。
 */
export type RankBarSize = 'mini' | 'large' | 'fluid'

/** 只有固定像素的两套才进 RANK_BAR，fluid 的宽度与步长由容器算 */
export type RankBarPxSize = Exclude<RankBarSize, 'fluid'>

interface RankBarSpec {
  /** 满格宽度 */
  width: number
  height: number
  /** 一段的步长（填充 + 白缝） */
  step: number
  /** 段间白缝 */
  gap: number
}

export const RANK_BAR: Record<RankBarPxSize, RankBarSpec> = {
  // 地图标记 / 卡片列表右列
  mini: { width: 30.4, height: 8, step: 4.5, gap: 1.1 },
  // 编辑页阶梯选择器下方的大号条
  large: { width: 40.2, height: 9, step: 5.9, gap: 1.1 },
}

/**
 * 通栏条：宽度 100%，7 段按容器宽度等分。
 * 只有高度与白缝是固定值 —— 塞进 RANK_BAR 就得把 width/step 写成 0，读的人会以为算错了。
 */
export const RANK_BAR_FLUID = { height: 7, gap: 1.6 } as const

export function clampLevel(level: number | null | undefined): number {
  if (level === null || level === undefined || Number.isNaN(level)) {
    return 0
  }
  return Math.min(Math.max(Math.trunc(level), 0), RANK_LEVEL_MAX)
}

export function rankOf(level: number | null | undefined): Rank | null {
  if (level === null || level === undefined) {
    return null
  }
  return RANKS[clampLevel(level)] ?? null
}

/**
 * 填充宽度 = 段数 × 步长 − 1.1（段数 = 等级序号 + 1，即 1~7 段）。
 * 迷你条：1 段 3.4px … 7 段 30.4px；大号条：1 段 4.8px … 7 段 40.2px。
 *
 * 靠长度差读数，所以精度比装饰重要 —— 不要加圆角模糊或渐变。
 * 禁止在组件里手写 width，一律调这个函数（手写过一次，7 处宽度对不上）。
 */
export function rankFillWidth(level: number | null | undefined, size: RankBarPxSize = 'mini'): number {
  const spec = RANK_BAR[size]
  const segments = level === null || level === undefined ? 0 : clampLevel(level) + 1
  return Math.max(0, Number((segments * spec.step - spec.gap).toFixed(2)))
}

/**
 * 通栏条的填充宽度：段数 / 7 的百分比，再减掉末端一道白缝。
 * 与 rankFillWidth 同一个公式（段数 × 步长 − 白缝），只是步长换成了百分比。
 */
export function rankFillFluid(level: number | null | undefined): string {
  const segments = level === null || level === undefined ? 0 : clampLevel(level) + 1
  const percent = ((segments / RANKS.length) * 100).toFixed(4)
  return `calc(${percent}% - ${RANK_BAR_FLUID.gap}px)`
}

/** 通栏条的段间白缝：7 段等分，白缝宽度固定 */
export function rankSlitFluid(): string {
  const edge = `calc(100% / ${RANKS.length} - ${RANK_BAR_FLUID.gap}px)`
  const step = `calc(100% / ${RANKS.length})`
  return (
    'repeating-linear-gradient(to right, transparent 0, ' +
    `transparent ${edge}, var(--bar-gap) ${edge}, var(--bar-gap) ${step})`
  )
}

/**
 * 地图标记的「环」表达状态，与刻度条（等级）分工，互不干扰。
 * 环只有 1.6px 厚，只能承载色相/明度的大幅跳变。
 *
 * 四值与 `spot.status` 一一对应，状态名与切换器配色见 config/status.ts。
 */
export type MarkerState = 'unvisited' | 'visited' | 'wishlist' | 'blacklist'

export const MARKER_RING: Record<MarkerState, string> = {
  unvisited: '#4e86a8', // 未打卡，钢蓝，中性
  visited: '#111318', // 已打卡，近黑
  wishlist: '#ff5a1e', // 想打卡，全场唯一「待办」强调色
  blacklist: '#c2c2c6', // 避雷，浅灰 + 头像去色 + 不显示刻度条
}

/** 认不出状态时按「未打卡」上环，不猜成已打卡 */
export function ringColor(status: string | null | undefined): string {
  return MARKER_RING[status as MarkerState] ?? MARKER_RING.unvisited
}
