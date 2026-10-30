/**
 * 标签分类（类型）的前端工具。
 *
 * **真实清单在数据库 `tag_category` 表里**（用户能在「我的 → 编辑标签」里新增 /
 * 删除类型），这里只保留一份兜底清单：接口还没回来或请求失败时，编辑页照样能把
 * 标签分好组，不会退化成一大坨平铺。
 *
 * 顺序即编辑页分组顺序：分类表按 `sort_order` 排，新类型追加在末尾。
 */
export interface TagCategory {
  code: string
  label: string
}

/** 兜底清单，与建表时的种子分类一致（`db/schema.sql` 的 tag_category） */
export const FALLBACK_TAG_CATEGORIES: TagCategory[] = [
  { code: 'person', label: '人物' },
  { code: 'place', label: '场所' },
  { code: 'service', label: '服务' },
  { code: 'vibe', label: '氛围' },
  { code: 'other', label: '其他' },
]

/** 兜底分类：新建标签默认落这里，分类被删时其下标签也归到这里；后端不允许删它 */
export const DEFAULT_TAG_CATEGORY = 'other'

/** code → 显示名；未知 code（分类刚被删、清单还没刷新）一律显示「其他」 */
export function tagCategoryLabel(
  code?: string | null,
  categories: TagCategory[] = FALLBACK_TAG_CATEGORIES,
): string {
  return categories.find((item) => item.code === code)?.label ?? '其他'
}

/** 分组结果：`items` 保持传入顺序（来自后端的全局 sort_order） */
export interface TagCategoryGroup<T> {
  code: string
  label: string
  items: T[]
}

/**
 * 按分类分组：空分类不返回，未知分类归到「其他」。
 *
 * 组与组的先后完全按 `categories`（即后端的 sort_order），组内顺序沿用传入顺序 ——
 * 编辑页看到的组内次序与标签管理页拖出来的优先级一致，这里再排一次会让两处对不上。
 */
export function groupTagsByCategory<T extends { category?: string | null }>(
  tags: T[],
  categories: TagCategory[] = FALLBACK_TAG_CATEGORIES,
): TagCategoryGroup<T>[] {
  const known = new Set(categories.map((item) => item.code))
  const buckets = new Map<string, T[]>()
  for (const tag of tags) {
    const raw = tag.category ?? DEFAULT_TAG_CATEGORY
    const code = known.has(raw) ? raw : DEFAULT_TAG_CATEGORY
    const list = buckets.get(code)
    if (list) {
      list.push(tag)
    } else {
      buckets.set(code, [tag])
    }
  }

  // 「其他」必须能兜住未知分类，万一清单里缺它就补在末尾，不能把标签吞掉
  const ordered: TagCategory[] = categories.some((item) => item.code === DEFAULT_TAG_CATEGORY)
    ? categories
    : [...categories, { code: DEFAULT_TAG_CATEGORY, label: '其他' }]

  return ordered
    .filter((item) => buckets.has(item.code))
    .map((item) => ({ code: item.code, label: item.label, items: buckets.get(item.code) as T[] }))
}
