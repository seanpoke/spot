import { http } from './http'

/**
 * 标签字典接口。
 *
 * 新建走「同名复用」：同名标签后端直接返回已有那一行，前端不用先去重。
 * 新建 / 改名 / 删除的唯一 UI 入口是「我的 → 编辑标签」（`/tags`），
 * 打卡点编辑页只做勾选。
 */
export interface TagItem {
  id: string
  name: string
  /** 标签颜色 #RRGGBB，可能为空（老标签没选过） */
  color?: string | null
  /** 分类 code，对应 tag_category.code；显示名由分类表换算 */
  category?: string | null
  sortOrder: number
  /** 被多少个打卡点使用；0 表示闲置 */
  useCount: number
}

/** 分类（类型）：code 是内部主键，label 是给人看的名 */
export interface TagCategoryItem {
  code: string
  label: string
  /** 顺序，1 起连续；编辑页分组顺序与标签管理页的类型排布都按它 */
  sortOrder: number
}

/** 删除类型的结果：movedTags 是被改归「其他」的标签数 */
export interface TagCategoryDeleteResult {
  code: string
  movedTags: number
}

/** 删除前的影响面：被几个点用、都是谁（后端最多回 5 个名字） */
export interface TagUsage {
  id: string
  name: string
  useCount: number
  sampleSpots: string[]
}

export interface TagDeleteResult {
  /** 连带解除的绑定数，0 表示这个标签本来没人用 */
  removedBindings: number
}

export function listTags(): Promise<TagItem[]> {
  return http.get<TagItem[]>('/tags')
}

/** 新建标签（同名复用），返回最终的标签对象。color / category 可选：新建时挑 */
export function createTag(
  name: string,
  color?: string | null,
  category?: string | null,
): Promise<TagItem> {
  return http.post<TagItem>('/tags', { name, color: color ?? null, category: category ?? null })
}

/** 改名 / 改色 / 改分类；color 传 null 就是清空颜色（回退到按名称稳定映射的兜底色） */
export function updateTag(
  id: string,
  name: string,
  color?: string | null,
  category?: string | null,
): Promise<TagItem> {
  return http.put<TagItem>(`/tags/${id}`, { name, color: color ?? null, category: category ?? null })
}

/**
 * 拖拽排序：按新顺序传**全部**标签 id，后端把 sort_order 重排成 1..n 并返回最新列表。
 *
 * 必须全量：只传被拖动的那几个，没传的会保持旧序号，很容易出现同号（同号时靠名字兜底，
 * 用户看到的就是「拖了没反应」）。数量对不上后端返回 409，前端提示刷新重试。
 */
export function sortTags(ids: string[]): Promise<TagItem[]> {
  return http.put<TagItem[]>('/tags/sort', { ids })
}

/**
 * 删除前的影响面预检（被几个点用、都是谁）。
 *
 * 标签管理页现在只弹一句确认就带 `force=true` 直删，**不再展示使用次数**；
 * 这个封装留给「需要先摊开影响面」的场景，接口本身仍在。
 */
export function getTagUsage(id: string): Promise<TagUsage> {
  return http.get<TagUsage>(`/tags/${id}/usage`)
}

/**
 * 删除标签（连带解除所有绑定）。
 *
 * `force` 必须由用户在确认框里点过「确定」才传 true：
 * 不带 force 且有绑定时后端返回 409 + 提示，不会静默摘掉一堆打卡点的标签。
 */
export function deleteTag(id: string, force = false): Promise<TagDeleteResult> {
  return http.delete<TagDeleteResult>(`/tags/${id}${force ? '?force=true' : ''}`)
}

/** 分类字典，按 sort_order 升序 */
export function listTagCategories(): Promise<TagCategoryItem[]> {
  return http.get<TagCategoryItem[]>('/tag-categories')
}

/** 新建分类：只传显示名，code 由后端生成 */
export function createTagCategory(label: string): Promise<TagCategoryItem> {
  return http.post<TagCategoryItem>('/tag-categories', { label })
}

/** 重命名分类：只改显示名，code 不动，所以不影响任何标签的归属；与别的类型重名返回 409 */
export function renameTagCategory(code: string, label: string): Promise<TagCategoryItem> {
  return http.put<TagCategoryItem>(`/tag-categories/${code}`, { label })
}

/**
 * 类型排序：按新顺序传**全部** code，后端把 sort_order 重排成 1..n 并返回最新列表。
 *
 * 与标签排序同一套约定：必须全量，数量对不上后端返回 409（提示刷新重试）。
 */
export function sortTagCategories(codes: string[]): Promise<TagCategoryItem[]> {
  return http.put<TagCategoryItem[]>('/tag-categories/sort', { codes })
}

/** 删除分类：该分类下的标签由后端改归「其他」，标签本身不删 */
export function deleteTagCategory(code: string): Promise<TagCategoryDeleteResult> {
  return http.delete<TagCategoryDeleteResult>(`/tag-categories/${code}`)
}
