/**
 * 地图标记的 DOM 工厂。
 *
 * 为什么不是 `.vue` 组件：高德 Marker 只接受「一个 HTML 元素」或 HTML 字符串，
 * 这些节点活在 Vue 的模板体系之外，做成 SFC 反而要多一层挂载/卸载的同步逻辑。
 * 所以这里用 createElement 手搓（不用 innerHTML —— 打卡点名字是用户输入，拼字符串会被 XSS 咬）。
 *
 * 事件不在这里绑：外层拿到元素后由视图调用 marker.on('click', ...)。
 */

import type { SpotListItem } from '@/api/spots'
import defaultAvatar from '@/assets/default-avatar.png'
import { RANKS, clampLevel, rankOf, ringColor } from '@/config/ranks'
import '@/styles/map.css'

/**
 * 白胶囊标记：头像（外圈彩环表状态）+ 名称 + 元信息 + 底部 7 段等级条。
 *
 * @param meta 元信息文案，如 `4.8 分 · 240m`；没有定位权限时只有状态，不显示距离
 */
export function createSpotMarker(spot: SpotListItem, meta: string, selected: boolean): HTMLElement {
  const root = document.createElement('div')
  root.className = 'mk'
  root.classList.toggle('mk--on', selected)
  root.classList.toggle('mk--x', spot.status === 'blacklist')

  const card = document.createElement('div')
  card.className = 'mk__c'

  const avatar = document.createElement('span')
  avatar.className = 'mk__av'
  avatar.style.setProperty('--ring', ringColor(spot.status))
  // 没头像、或图片加载失败，都退回本地默认头像。
  // 这里的 DOM 活在 Vue 之外（高德 Marker 只收元素），所以兜底手写，没有 SafeImage 可用
  const img = document.createElement('img')
  img.alt = ''
  img.loading = 'lazy'
  img.decoding = 'async'
  img.src = spot.avatar || defaultAvatar
  let fellBack = false
  img.addEventListener('error', () => {
    if (fellBack) {
      return
    }
    fellBack = true
    img.src = defaultAvatar
  })
  avatar.appendChild(img)

  const text = document.createElement('span')
  text.className = 'mk__tx'
  const name = document.createElement('b')
  name.textContent = spot.name
  const sub = document.createElement('span')
  sub.textContent = meta
  text.append(name, sub)

  card.append(avatar, text)

  // 避雷不显示等级条（AGENTS.md 第 23 条）：避雷的语义与「推荐等级」互斥
  if (spot.status !== 'blacklist') {
    const bar = document.createElement('span')
    bar.className = 'mk__bar'
    bar.style.setProperty('--bc', rankOf(spot.rankLevel)?.color ?? 'var(--bar-empty)')
    const filled = clampLevel(spot.rankLevel) + 1
    for (let i = 0; i < RANKS.length; i += 1) {
      const segment = document.createElement('i')
      if (i < filled) {
        segment.className = 'on'
      }
      bar.appendChild(segment)
    }
    card.appendChild(bar)
  }

  root.appendChild(card)
  return root
}

/**
 * 当前位置的小蓝点。
 *
 * @param temp true = 临时坐标生效中：换成橙心，跟真实定位一眼区分开
 *
 * 类名统一带 `mk-` 前缀：这些节点挂在全局样式表（styles/map.css）下，
 * 用 `.me` 这种通用名会跟页面上的同名类互相污染。
 */
export function createMeMarker(temp = false): HTMLElement {
  const root = document.createElement('div')
  root.className = temp ? 'mk-me mk-me--temp' : 'mk-me'
  const halo = document.createElement('span')
  halo.className = 'mk-me__halo'
  const core = document.createElement('span')
  core.className = 'mk-me__core'
  root.append(halo, core)
  return root
}
