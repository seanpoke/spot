/**
 * 打卡状态 —— 全站唯一来源（名称与颜色都只在这里写一次）。
 *
 * 四个状态与 `spot.status` 一一对应（db/schema.sql）：
 *   unvisited 未打卡 / visited 已打卡 / wishlist 想打卡 / blacklist 避雷
 *
 * 颜色分工：
 *   - `ring` 给地图标记的外环（只承载色相/明度的大幅跳变，见 styles/map.css）
 *   - `background` / `foreground` 给编辑页的状态切换器，让四个状态一眼能分开
 *
 * 避雷的环是 #c2c2c6（很浅，靠「头像去色 + 不显示刻度条」再区分），
 * 但这么浅的底托不住白字，所以切换器上用更深一档的 #8a9099。
 */
import type { SpotStatus } from '@/api/spots'
import { MARKER_RING } from './ranks'

export interface SpotStatusMeta {
  value: SpotStatus
  label: string
  /** 地图标记外环色 */
  ring: string
  /** 状态切换器选中时的底色 */
  background: string
  /** 状态切换器选中时的字色 */
  foreground: string
}

export const SPOT_STATUSES: readonly SpotStatusMeta[] = [
  {
    value: 'unvisited',
    label: '未打卡',
    ring: MARKER_RING.unvisited,
    background: MARKER_RING.unvisited,
    foreground: '#ffffff',
  },
  {
    value: 'visited',
    label: '已打卡',
    ring: MARKER_RING.visited,
    background: MARKER_RING.visited,
    foreground: '#ffffff',
  },
  {
    value: 'wishlist',
    label: '想打卡',
    ring: MARKER_RING.wishlist,
    background: MARKER_RING.wishlist,
    foreground: '#ffffff',
  },
  {
    value: 'blacklist',
    label: '避雷',
    ring: MARKER_RING.blacklist,
    background: '#8a9099',
    foreground: '#ffffff',
  },
]

/** 新建打卡点的默认状态：先记下来，去没去过另说 */
export const DEFAULT_STATUS: SpotStatus = 'unvisited'

/** 后端未来若加了新状态而前端还没跟上，退回「未打卡」比显示空白好 */
export function statusMeta(status: string | null | undefined): SpotStatusMeta {
  return SPOT_STATUSES.find((item) => item.value === status) ?? SPOT_STATUSES[0]
}

export function statusLabel(status: string | null | undefined): string {
  return statusMeta(status).label
}
