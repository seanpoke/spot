import { http } from './http'

/**
 * 标签字典接口。
 *
 * 新建走「同名复用」：同名标签后端直接返回已有那一行，前端不用先去重。
 */
export interface TagItem {
  id: string
  name: string
  /** 标签颜色 #RRGGBB，可能为空（老标签没选过） */
  color?: string | null
  sortOrder: number
  /** 被多少个打卡点使用；0 表示闲置 */
  useCount: number
}

export function listTags(): Promise<TagItem[]> {
  return http.get<TagItem[]>('/tags')
}

/** 新建标签（同名复用），返回最终的标签对象。color 可选：让前端在新建时挑颜色 */
export function createTag(name: string, color?: string | null): Promise<TagItem> {
  return http.post<TagItem>('/tags', { name, color: color ?? null })
}

export function deleteTag(id: string): Promise<void> {
  return http.delete<void>(`/tags/${id}`)
}
