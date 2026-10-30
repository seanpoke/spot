import { ref } from 'vue'
import { listTagCategories, type TagCategoryItem } from '@/api/tags'
import { FALLBACK_TAG_CATEGORIES, type TagCategory } from '@/config/tagCategories'

/**
 * 标签分类清单（进程内单例）。
 *
 * 分类存在库里，编辑页（分组）与标签管理页（类型行）都要用同一份，所以在模块级
 * 缓存一次：两个页面互相跳转不用各请求一遍。`ensure()` 只拉一次，`reload()` 强制
 * 刷新（新增 / 删除类型后调）。
 *
 * 接口失败时**保留兜底清单**不报错：分类只是分组用的辅助信息，不该因为它把整页
 * 打成空白；真需要提示的地方（标签管理页自己的错误）由页面负责。
 */
const categories = ref<TagCategory[]>([...FALLBACK_TAG_CATEGORIES])
const loaded = ref(false)
let inflight: Promise<void> | null = null

/** 后端出空表或不含「其他」时视为异常响应，别用坏数据盖掉兜底清单 */
function adopt(items: TagCategoryItem[]): void {
  const clean = items
    .filter((item) => !!item?.code && !!item?.label)
    .map((item) => ({ code: item.code, label: item.label }))
  if (!clean.some((item) => item.code === 'other')) return
  categories.value = clean
}

async function fetchCategories(): Promise<void> {
  try {
    adopt(await listTagCategories())
    loaded.value = true
  } catch {
    // 保持现状（兜底清单或上一次的好数据）
  }
}

export function useTagCategories() {
  /** 首次使用时拉一次；已经拉过就直接返回 */
  async function ensure(): Promise<void> {
    if (loaded.value) return
    if (!inflight) {
      inflight = fetchCategories().finally(() => {
        inflight = null
      })
    }
    await inflight
  }

  /** 强制刷新：新增 / 删除类型之后调，保证顺序和 label 与库里一致 */
  async function reload(): Promise<void> {
    if (!inflight) {
      inflight = fetchCategories().finally(() => {
        inflight = null
      })
    }
    await inflight
  }

  return { categories, loaded, ensure, reload }
}
