import type { SpotContactType } from '@/api/spots'

/**
 * 联系方式类型的**唯一来源**（类型、展示名、输入提示、点击行为都在这里）。
 *
 * 与后端 `SpotService.CONTACT_TYPES` 的白名单一一对应：加一种新类型时两边一起加，
 * 前端加一项、后端加一个枚举值即可，不需要改表结构（联系方式已经是独立表）。
 *
 * 数组顺序 = 编辑页「+ 添加」时的默认候选顺序 = 详情页的展示分组顺序。
 * 电话放第一位：它是这张卡里最常用的一个。
 */
export interface ContactTypeMeta {
  value: SpotContactType
  /** 编辑页左侧类型块上的名字 */
  label: string
  /** 详情页左侧的小徽标（两三个字，宽了会挤掉值） */
  badge: string
  /** 输入框提示，带上示例比一句「请输入」有用 */
  placeholder: string
  /** 手机键盘：电话用 tel，其余用 text（微信号常含字母） */
  inputMode: 'tel' | 'text'
  /**
   * 详情页点一下做什么：
   *   dial      拨号（电话）
   *   copy      只复制（微信 / 与你：没有能直达账号的公开 scheme）
   *   copy-open 复制后拉起 App，粘贴即可搜（微信号、QQ、Telegram）
   */
  action: 'dial' | 'copy' | 'copy-open'
}

export const CONTACT_TYPES: ContactTypeMeta[] = [
  {
    value: 'phone',
    label: '电话',
    badge: '电话',
    placeholder: '手机号或座机，如 13800138000',
    inputMode: 'tel',
    action: 'dial',
  },
  {
    value: 'wechat',
    label: '微信',
    badge: '微信',
    placeholder: '微信号 / 绑定手机号',
    inputMode: 'text',
    action: 'copy-open',
  },
  {
    value: 'qq',
    label: 'QQ',
    badge: 'QQ',
    placeholder: 'QQ 号',
    inputMode: 'text',
    action: 'copy-open',
  },
  {
    value: 'telegram',
    label: 'Telegram',
    badge: 'TG',
    placeholder: 'Telegram 用户名，不带 @',
    inputMode: 'text',
    action: 'copy-open',
  },
  {
    value: 'youyu',
    label: '与你',
    badge: '与你',
    // 值可以是纯 ID（拼 yuni://open/profile?uid=），也可以整条粘二维码链接（原样打开）
    placeholder: '「与你」ID，或整条 yuni:// 二维码链接',
    inputMode: 'text',
    action: 'copy-open',
  },
]

const META = new Map(CONTACT_TYPES.map((item) => [item.value, item]))

/**
 * 「联系方式」页（详情页那张卡跳过去的独立页）的分组顺序。
 *
 * 刻意与 `CONTACT_TYPES` 不同：那个是**编辑页**的候选顺序（电话最常用，排第一），
 * 这里是**阅读**顺序 —— 社交账号在前、电话兜底。不在表里的未知类型按首次出现追加到末尾，
 * 别把后端先加、前端还没跟上的新类型吞掉。
 */
export const CONTACT_GROUP_ORDER: SpotContactType[] = ['qq', 'wechat', 'telegram', 'youyu', 'phone']

export interface ContactGroup<T> {
  type: SpotContactType
  label: string
  badge: string
  items: T[]
}

/**
 * 把一条条联系方式按类型分组（保持组内原有顺序）。
 *
 * 只返回**有内容的组**：没填的类型不占位，联系方式页不留空标题。
 */
export function groupContacts<T extends { type: SpotContactType }>(items: T[]): ContactGroup<T>[] {
  const buckets = new Map<string, T[]>()
  for (const item of items) {
    const bucket = buckets.get(item.type)
    if (bucket) {
      bucket.push(item)
    } else {
      buckets.set(item.type, [item])
    }
  }

  const known = new Set<string>(CONTACT_GROUP_ORDER)
  const order = [...CONTACT_GROUP_ORDER, ...[...buckets.keys()].filter((type) => !known.has(type))]

  return order
    .filter((type) => buckets.has(type))
    .map((type) => {
      const meta = contactMeta(type)
      return { type: meta.value, label: meta.label, badge: meta.badge, items: buckets.get(type) as T[] }
    })
}

/** 未知类型（后端先加了新类型、前端还没跟上）时给一个能用的兜底，别让整块不渲染 */
export function contactMeta(type: string): ContactTypeMeta {
  return (
    META.get(type as SpotContactType) ?? {
      value: type as SpotContactType,
      label: type,
      badge: type.slice(0, 2).toUpperCase(),
      placeholder: '',
      inputMode: 'text',
      action: 'copy',
    }
  )
}

/** 电话拨号只留数字与前导 +，空格 / 括号 / 横线在 tel: 里没意义 */
export function dialNumber(value: string): string {
  const trimmed = value.trim()
  const plus = trimmed.startsWith('+') ? '+' : ''
  return plus + trimmed.replace(/\D/g, '')
}

/**
 * 能拉起对应 App 的协议。
 *
 * **微信没有「带关键字打开搜索框」的公开 scheme**（截图里那个搜索页是 App 内部页面，
 * 外部无法直接定位过去），所以只能 `weixin://` 拉起微信，让用户粘贴刚复制的微信号。
 * QQ / Telegram 有 resolve 协议，可以直接定位到账号。
 *
 * **「与你」的 scheme 来自个人二维码**：`yuni://open/profile?uid=…&token=…&timestamp=…`。
 * 二维码里除了 uid 还带 token + timestamp（App 生成的分享凭证，前端算不出来），
 * 所以这里只拼 `uid`：
 *   - 值填纯 ID  → 拼 `yuni://open/profile?uid=<ID>`，能拉起 App 并尝试打开该用户；
 *   - 值填整条 yuni:// 链接（含 token）→ **原样打开**，这条最稳，二维码怎么写的就怎么用。
 * 如果某个用户按 uid 打不开（App 要求 token），让他把二维码链接整条粘进来即可。
 */
export function appScheme(type: SpotContactType, value: string): string | null {
  switch (type) {
    case 'wechat':
      return 'weixin://'
    case 'phone':
      return `tel:${dialNumber(value)}`
    case 'qq':
      return `mqq://im/chat?chat_type=wpa&uin=${encodeURIComponent(value.trim())}`
    case 'telegram':
      return `tg://resolve?domain=${encodeURIComponent(value.trim().replace(/^@/, ''))}`
    case 'youyu': {
      const raw = value.trim()
      if (/^yuni:\/\//i.test(raw)) {
        return raw
      }
      return `yuni://open/profile?uid=${encodeURIComponent(raw)}`
    }
    default:
      return null
  }
}
