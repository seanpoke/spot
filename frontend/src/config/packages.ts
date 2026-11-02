import type { ConfigItem } from '@/api/configs'

/**
 * 消费项目预设的**兜底清单**。
 *
 * 真实数据在 `base_config` 表里（`code = 'priceItem'`），由
 * `GET /api/configs?code=priceItem` 拉取；接口失败或还没回来时用这份，
 * 保证编辑页永远能加项目（预设只是快捷方式，后端只存 `name + price`，
 * 不认这些名字，所以兜底清单多一条少一条都不影响保存）。
 *
 * 改预设请改库（`db/migrations/20261102_base_config.sql` 里灌了种子），不要再只改这里。
 */
export const FALLBACK_PRICE_ITEMS: ConfigItem[] = [
  { key: '1P', value: '0', desc: '一次' },
  { key: '2P', value: '0', desc: '两次' },
  { key: '夜', value: '0', desc: '过夜' },
]

/** 预设里配了默认价格就带上，0 / 空 / 非数字一律当「不预填」 */
export function presetPrice(value: string): string {
  const price = Number(value)
  return Number.isInteger(price) && price > 0 ? String(price) : ''
}
