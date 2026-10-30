<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ActionSheet from '@/components/ActionSheet.vue'
import {
  createTag,
  createTagCategory,
  deleteTag,
  deleteTagCategory,
  listTags,
  renameTagCategory,
  sortTagCategories,
  sortTags,
  updateTag,
  type TagItem,
} from '@/api/tags'
import { HttpError } from '@/api/http'
import { TAG_PALETTE, resolveTagColor } from '@/utils/tagColor'
import { DEFAULT_TAG_CATEGORY, tagCategoryLabel, type TagCategory } from '@/config/tagCategories'
import { useTagCategories } from '@/composables/useTagCategories'

/**
 * 标签管理页（`/tags`，从「我的」进）。
 *
 * 为什么把它独立出来：标签是全库共享的字典，新建 / 改名 / 删除都会影响所有挂着它的
 * 打卡点。以前在打卡点编辑页顺手新建，很容易攒出「按摩 / 按摩店 / 推拿」这种同义项；
 * 删标签更是破坏性操作 —— 必须在一个「只看标签」的地方做。
 *
 * 交互约定（2026-10-30 收敛）：
 *   1. 每行只留一个 ⋯ 按钮，点开是【修改 / 切换类型 / 删除】—— 一行里不再并排
 *      三个按钮，也不再显示「被几个点用 / 未使用」这类状态字，列表只回答「有哪些标签」
 *   2. 类型（分类）可以在本页新增和删除；删类型不删标签，它们归到「其他」
 *   3. 新建同名 → 后端复用已有那行，前端只说「已存在」，不产生第二条
 *   4. 改名重名 → 后端 409，直接把它的中文提示显示出来，停在编辑态让用户改
 *   5. 删除前弹一次确认（会连带摘掉打卡点上的这个标签），确认后带 force 直删
 */

const router = useRouter()
const { categories, ensure: ensureCategories, reload: reloadCategories } = useTagCategories()

const tags = ref<TagItem[]>([])
const loading = ref(true)

/** 新建区 */
const newName = ref('')
const newColor = ref<string | null>(TAG_PALETTE[0])
const newCategory = ref(DEFAULT_TAG_CATEGORY)
const creating = ref(false)

/** 类型区：新增类型用一个就地展开的输入行，不弹窗 */
const addingCategory = ref(false)
const newCategoryLabel = ref('')
const creatingCategory = ref(false)
const categoryInput = ref<HTMLInputElement | null>(null)

/** 行内编辑：只改名字与颜色，类型走单独入口（⋯ → 切换类型） */
const editingId = ref('')
const editName = ref('')
const editColor = ref<string | null>(null)
const savingId = ref('')

/** 行操作面板 / 类型选择面板 / 类型自身操作面板（同一个 ActionSheet 组件，三份状态） */
const rowSheet = ref<TagItem | null>(null)
const pickTarget = ref<TagItem | 'new' | null>(null)
const typeSheet = ref<TagCategory | null>(null)

const editInput = ref<HTMLInputElement | null>(null)

const rowActions = computed(() => [
  { key: 'edit', label: '修改' },
  {
    key: 'category',
    label: '切换类型',
    hint: rowSheet.value ? tagCategoryLabel(rowSheet.value.category, categories.value) : '',
  },
  { key: 'remove', label: '删除' },
])

/** 选择面板里高亮「当前」：新建时看 newCategory，切换时看那一行自己的分类 */
const pickCurrent = computed(() => {
  if (pickTarget.value === 'new') return newCategory.value
  return pickTarget.value?.category ?? DEFAULT_TAG_CATEGORY
})

const pickActions = computed(() =>
  categories.value.map((cat) => ({
    key: cat.code,
    label: cat.label,
    hint: cat.code === pickCurrent.value ? '当前' : '',
  })),
)

/** 类型面板：只列出此刻做的了的动作 —— 第一个没有「上移」，最后一个没有「下移」，「其他」不能删 */
const typeIndex = computed(() =>
  typeSheet.value ? categories.value.findIndex((cat) => cat.code === typeSheet.value?.code) : -1,
)

const typeActions = computed(() => {
  const actions: { key: string; label: string }[] = [{ key: 'rename', label: '重命名' }]
  if (typeIndex.value > 0) {
    actions.push({ key: 'up', label: '上移' })
  }
  if (typeIndex.value >= 0 && typeIndex.value < categories.value.length - 1) {
    actions.push({ key: 'down', label: '下移' })
  }
  if (typeSheet.value && typeSheet.value.code !== DEFAULT_TAG_CATEGORY) {
    actions.push({ key: 'remove', label: '删除' })
  }
  return actions
})

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    const [tagList] = await Promise.all([listTags(), ensureCategories()])
    tags.value = tagList
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    loading.value = false
  }
}

function messageOf(error: unknown): string {
  return error instanceof HttpError ? error.message : '操作失败，请重试'
}

/** ElMessageBox 取消时 reject 的是 'cancel' / 'close' 字符串，不是 Error */
function cancelled(error: unknown): boolean {
  return error === 'cancel' || error === 'close'
}

/* ---- 新建标签 ---- */

async function create(): Promise<void> {
  if (creating.value) return
  const name = newName.value.trim()
  if (!name) {
    ElMessage.warning('先敲一个标签名')
    return
  }
  // 本地先挡一道，省一次往返；真正的唯一性仍由后端 uk_tag_name 决定
  if (tags.value.some((tag) => tag.name === name)) {
    ElMessage.warning(`「${name}」已经在标签库里了`)
    return
  }

  creating.value = true
  try {
    const tag = await createTag(name, newColor.value, newCategory.value)
    const existed = tags.value.some((item) => item.id === tag.id)
    tags.value = existed
      ? tags.value.map((item) => (item.id === tag.id ? tag : item))
      : [...tags.value, tag]
    newName.value = ''
    ElMessage.success(existed ? `「${tag.name}」已存在，没有重复新建` : `已新建「${tag.name}」`)
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    creating.value = false
  }
}

/* ---- 类型（分类）增删 ---- */

function setCategoryInput(el: unknown): void {
  if (el instanceof HTMLInputElement) {
    categoryInput.value = el
  }
}

function startAddCategory(): void {
  addingCategory.value = true
  void nextTick(() => categoryInput.value?.focus())
}

function cancelAddCategory(): void {
  addingCategory.value = false
  newCategoryLabel.value = ''
}

async function submitCategory(): Promise<void> {
  if (creatingCategory.value) return
  const label = newCategoryLabel.value.trim()
  if (!label) {
    ElMessage.warning('先敲一个类型名')
    return
  }
  if (categories.value.some((cat) => cat.label === label)) {
    ElMessage.warning(`已经有「${label}」这个类型了`)
    return
  }

  creatingCategory.value = true
  try {
    const created = await createTagCategory(label)
    await reloadCategories()
    // 刚建的类型大概率就是给下一个标签用的，直接选中它
    newCategory.value = created.code
    cancelAddCategory()
    ElMessage.success(`已新增类型「${created.label}」`)
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    creatingCategory.value = false
  }
}

function openTypeSheet(cat: TagCategory): void {
  typeSheet.value = cat
}

function onTypeAction(key: string): void {
  const cat = typeSheet.value
  typeSheet.value = null
  if (!cat) return
  if (key === 'rename') {
    void renameCategory(cat)
    return
  }
  if (key === 'up' || key === 'down') {
    void moveCategory(cat, key === 'up' ? -1 : 1)
    return
  }
  if (key === 'remove') {
    void removeCategory(cat)
  }
}

/** 改名只动 label，code 不变，所以不会影响任何标签的归属 */
async function renameCategory(cat: TagCategory): Promise<void> {
  let label = ''
  try {
    const result = await ElMessageBox.prompt(
      '类型名最多 12 个字，改名后用到它的标签会一起显示新名字。',
      `重命名「${cat.label}」`,
      {
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValue: cat.label,
        inputPlaceholder: '类型名',
        inputValidator: (value: string) => {
          const text = (value ?? '').trim()
          if (!text) return '类型名不能为空'
          return text.length > 12 ? '最多 12 个字' : true
        },
      },
    )
    label = String(result.value ?? '').trim()
  } catch (error) {
    if (cancelled(error)) return
    return
  }
  if (!label || label === cat.label) return

  try {
    await renameTagCategory(cat.code, label)
    await reloadCategories()
    ElMessage.success(`已改名为「${label}」`)
  } catch (error) {
    ElMessage.error(messageOf(error))
  }
}

/**
 * 上移 / 下移一格：先改本地数组（界面立刻动），再把全量顺序提交 1..n。
 *
 * 类型数量本来就少（个位数），不做拖拽 —— 手指在几颗胶囊上长按更累，
 * 点开面板点一下更快，也和标签行的「点开选项」保持同一套手感。
 */
async function moveCategory(cat: TagCategory, delta: number): Promise<void> {
  const list = [...categories.value]
  const from = list.findIndex((item) => item.code === cat.code)
  const to = from + delta
  if (from < 0 || to < 0 || to >= list.length) return
  const [moved] = list.splice(from, 1)
  list.splice(to, 0, moved)
  categories.value = list

  try {
    const latest = await sortTagCategories(list.map((item) => item.code))
    categories.value = latest.map((item) => ({ code: item.code, label: item.label }))
  } catch (error) {
    ElMessage.error(messageOf(error))
    // 与服务端对齐，别让界面停在没保存的顺序上
    await reloadCategories()
  }
}

/**
 * 删类型：只动分类字典，标签一个不少 —— 该类型的标签在后端改归「其他」。
 * 不删标签是因为「一次删掉十几个标签」远比「它们挤进其他」难收拾。
 */
async function removeCategory(cat: TagCategory): Promise<void> {
  const used = tags.value.filter((tag) => (tag.category ?? DEFAULT_TAG_CATEGORY) === cat.code).length
  try {
    await ElMessageBox.confirm(
      used > 0
        ? `「${cat.label}」下有 ${used} 个标签，删掉类型后它们会归到「其他」，标签本身不会消失。`
        : `「${cat.label}」下面还没有标签，可以直接删掉。`,
      `删除类型「${cat.label}」`,
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch (error) {
    if (cancelled(error)) return
    return
  }

  try {
    const result = await deleteTagCategory(cat.code)
    // 本地同步：这些标签已经在后端改归 other，不刷新也会被 groupTagsByCategory 收进「其他」
    tags.value = tags.value.map((tag) =>
      (tag.category ?? DEFAULT_TAG_CATEGORY) === cat.code
        ? { ...tag, category: DEFAULT_TAG_CATEGORY }
        : tag,
    )
    if (newCategory.value === cat.code) {
      newCategory.value = DEFAULT_TAG_CATEGORY
    }
    await reloadCategories()
    ElMessage.success(
      result.movedTags > 0 ? `已删除类型，${result.movedTags} 个标签归到「其他」` : '已删除类型',
    )
  } catch (error) {
    ElMessage.error(messageOf(error))
  }
}

/* ---- 行操作：⋯ → 修改 / 切换类型 / 删除 ---- */

function openRowSheet(tag: TagItem): void {
  if (savingId.value) return
  rowSheet.value = tag
}

function onRowAction(key: string): void {
  const tag = rowSheet.value
  rowSheet.value = null
  if (!tag) return
  if (key === 'edit') {
    startEdit(tag)
    return
  }
  if (key === 'category') {
    pickTarget.value = tag
    return
  }
  if (key === 'remove') {
    void remove(tag)
  }
}

async function onPickCategory(code: string): Promise<void> {
  const target = pickTarget.value
  pickTarget.value = null
  if (target === 'new') {
    newCategory.value = code
    return
  }
  if (!target || code === (target.category ?? DEFAULT_TAG_CATEGORY)) return

  savingId.value = target.id
  try {
    const updated = await updateTag(target.id, target.name, target.color ?? null, code)
    tags.value = tags.value.map((item) => (item.id === updated.id ? updated : item))
    ElMessage.success(`已归到「${tagCategoryLabel(code, categories.value)}」`)
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    savingId.value = ''
  }
}

/* ---- 行内改名字 / 改颜色 ---- */

function setEditInput(el: unknown): void {
  if (el instanceof HTMLInputElement) {
    editInput.value = el
  }
}

function startEdit(tag: TagItem): void {
  if (savingId.value) return
  editingId.value = tag.id
  editName.value = tag.name
  editColor.value = tag.color ?? null
  void nextTick(() => editInput.value?.focus())
}

function cancelEdit(): void {
  editingId.value = ''
  editName.value = ''
  editColor.value = null
}

async function submitEdit(tag: TagItem): Promise<void> {
  if (savingId.value) return
  const name = editName.value.trim()
  if (!name) {
    ElMessage.warning('标签名不能为空')
    return
  }
  // 什么都没改就直接退出，不打接口（也避免「同名冲突」被自己的旧名字挡下来）
  if (name === tag.name && (editColor.value ?? null) === (tag.color ?? null)) {
    cancelEdit()
    return
  }

  savingId.value = tag.id
  try {
    const updated = await updateTag(tag.id, name, editColor.value, tag.category ?? DEFAULT_TAG_CATEGORY)
    tags.value = tags.value.map((item) => (item.id === tag.id ? updated : item))
    cancelEdit()
    ElMessage.success('已保存')
  } catch (error) {
    // 409「已有同名标签」的文案是后端写好的，直接给用户看，并停在编辑态
    ElMessage.error(messageOf(error))
  } finally {
    savingId.value = ''
  }
}

/* ---- 删除标签 ---- */

function dropFromList(id: string): void {
  tags.value = tags.value.filter((item) => item.id !== id)
  if (editingId.value === id) {
    cancelEdit()
  }
}

async function remove(tag: TagItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除「${tag.name}」后，挂着它的打卡点会一并摘掉这个标签，打卡点本身、照片和到访记录都不受影响。`,
      '删除标签',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch (error) {
    if (cancelled(error)) return
    return
  }

  try {
    const result = await deleteTag(tag.id, true)
    dropFromList(tag.id)
    ElMessage.success(
      result.removedBindings > 0
        ? `已删除，并从 ${result.removedBindings} 个打卡点上摘下`
        : '已删除',
    )
  } catch (error) {
    ElMessage.error(messageOf(error))
  }
}

/* ---- 长按拖拽排序 ---- */
/**
 * 长按 350ms 进入拖拽，之后整行跟着手指走，经过谁就插到谁的位置。
 * 松手才调一次接口（全量提交 1..n），拖动过程中不动网络。
 *
 * 用 Pointer Events 而不是 HTML5 draggable：后者在移动端基本不工作。
 * 在按钮上按下不启动（否则「⋯」按久了会变成拖行）；
 * 长按未生效就移动超过 8px 视为滚动，取消长按。
 */
const listEl = ref<HTMLUListElement | null>(null)
const draggingId = ref('')
const savingOrder = ref(false)

let pressTimer: ReturnType<typeof setTimeout> | undefined
let pressStartY = 0
let orderChanged = false

/**
 * 拖拽期间拦掉的 touchmove。
 *
 * 这是「一卡一卡」的根因：不拦的话，手指纵向移动会被浏览器当成页面滚动，
 * 一旦开始滚动就会对我们发 `pointercancel` —— 拖拽当场结束、顺手把当前顺序存一次，
 * 于是每经过一个标签就停一下。拦截后滚动归我们管，指针事件可以一路跟到松手。
 */
let blockScroll: ((event: TouchEvent) => void) | null = null

function onRowPressStart(event: PointerEvent, index: number): void {
  if (event.button !== 0 && event.pointerType === 'mouse') return
  const target = event.target as HTMLElement
  // 行内按钮（⋯ / 上下移 / 色板）不参与拖拽
  if (target.closest('button') || target.closest('input')) return
  // 行内编辑中不拖：那时整行是表单，拖动会打断输入
  if (editingId.value || savingOrder.value) return

  pressStartY = event.clientY
  orderChanged = false
  window.clearTimeout(pressTimer)
  pressTimer = setTimeout(() => beginDrag(index), 350)
  window.addEventListener('pointermove', onRowPressMove)
  window.addEventListener('pointerup', onRowPressEnd)
  window.addEventListener('pointercancel', onRowPressEnd)
}

function beginDrag(index: number): void {
  const tag = tags.value[index]
  if (!tag) return
  draggingId.value = tag.id
  // 拖的时候别选中文字；滚动由 blockScroll 接管（不动 body 的 overflow，
  // 那会让滚动条一闪、整个页面横向抖一下）
  document.body.style.userSelect = 'none'
  blockScroll = (event: TouchEvent) => {
    if (event.cancelable) {
      event.preventDefault()
    }
  }
  window.addEventListener('touchmove', blockScroll, { passive: false })
}

function releaseDrag(): void {
  document.body.style.userSelect = ''
  if (blockScroll) {
    window.removeEventListener('touchmove', blockScroll)
    blockScroll = null
  }
}

function onRowPressMove(event: PointerEvent): void {
  if (!draggingId.value) {
    // 长按还没生效就滑动 → 用户是在滚页面，别抢
    if (Math.abs(event.clientY - pressStartY) > 8) stopPress()
    return
  }
  const from = tags.value.findIndex((tag) => tag.id === draggingId.value)
  const to = indexFromPoint(event.clientY)
  if (from < 0 || to < 0 || to === from) return
  const [moved] = tags.value.splice(from, 1)
  tags.value.splice(to, 0, moved)
  orderChanged = true
}

/** 指针落在第几行（按每行中线判断，过了中线就算插到下一行） */
function indexFromPoint(y: number): number {
  const rows = [...(listEl.value?.children ?? [])] as HTMLElement[]
  for (let index = 0; index < rows.length; index += 1) {
    const rect = rows[index].getBoundingClientRect()
    if (y < rect.top + rect.height / 2) {
      return index
    }
  }
  return rows.length - 1
}

function onRowPressEnd(): void {
  const dragged = !!draggingId.value
  stopPress()
  draggingId.value = ''
  releaseDrag()
  if (dragged && orderChanged) {
    orderChanged = false
    scheduleSave()
  }
}

function stopPress(): void {
  window.clearTimeout(pressTimer)
  window.removeEventListener('pointermove', onRowPressMove)
  window.removeEventListener('pointerup', onRowPressEnd)
  window.removeEventListener('pointercancel', onRowPressEnd)
}

/* ---- 上下移动（拖拽的替代：手不灵活时一下一下点更稳） ---- */
/**
 * 把第 index 个标签上移（delta=-1）或下移（delta=1）。
 *
 * 只改本地数组，落库交给防抖的 {@link scheduleSave} ——
 * 连点三次上移只发一次请求，而不是每点一下等着转圈。
 */
function moveTag(index: number, delta: number): void {
  const target = index + delta
  if (target < 0 || target >= tags.value.length) return
  const [moved] = tags.value.splice(index, 1)
  tags.value.splice(target, 0, moved)
  scheduleSave()
}

let saveTimer: ReturnType<typeof setTimeout> | undefined
/** 保存排队中：上一次请求还没回来时又改了顺序 */
let savePending = false

/** 防抖 250ms：拖拽连续挪位、或连点上下移，都只在停手后发一次 */
function scheduleSave(): void {
  window.clearTimeout(saveTimer)
  saveTimer = setTimeout(() => void flushSort(), 250)
}

async function flushSort(): Promise<void> {
  if (savingOrder.value) {
    // 正在提交，等它回来再补一次（不能直接丢，否则最后一笔改动丢失）
    savePending = true
    return
  }
  savingOrder.value = true
  try {
    const latest = await sortTags(tags.value.map((tag) => tag.id))
    // 只同步使用次数等字段，**不覆盖本地顺序**：请求飞行期间用户可能又挪了
    tags.value = tags.value.map((tag) => latest.find((item) => item.id === tag.id) ?? tag)
  } catch (error) {
    ElMessage.error(messageOf(error))
    // 与服务端对齐，别让界面停在没保存的顺序上
    await load()
  } finally {
    savingOrder.value = false
    if (savePending) {
      savePending = false
      scheduleSave()
    }
  }
}

function goBack(): void {
  void router.replace('/settings')
}
</script>

<template>
  <div class="page">
    <header class="head">
      <button class="head__back" type="button" aria-label="返回" @click="goBack()">←</button>
      <div class="head__text">
        <h1 class="page-title">标签管理</h1>
        <p class="meta">点行尾 ⋯ 修改、切换类型或删除 · 长按拖动排序</p>
      </div>
    </header>

    <!-- 类型：可以新增和删除。删类型不删标签，它们归到「其他」 -->
    <section class="card block">
      <div class="block__title">
        <h2 class="section-title">类型</h2>
        <span class="meta">点类型可改名、调顺序、删除</span>
      </div>

      <div class="cats">
        <button
          v-for="cat in categories"
          :key="cat.code"
          class="cat"
          type="button"
          :aria-label="`类型 ${cat.label}`"
          @click="openTypeSheet(cat)"
        >
          {{ cat.label }}
        </button>
        <button v-if="!addingCategory" class="cat cat--add" type="button" @click="startAddCategory()">
          ＋ 新类型
        </button>
      </div>

      <div v-if="addingCategory" class="newrow">
        <input
          :ref="setCategoryInput"
          v-model="newCategoryLabel"
          class="taginput"
          type="text"
          maxlength="12"
          placeholder="类型名，如「身材」"
          @keyup.enter.prevent="submitCategory()"
        />
        <button class="btn newrow__btn" type="button" :disabled="creatingCategory" @click="submitCategory()">
          {{ creatingCategory ? '…' : '添加' }}
        </button>
        <button class="op" type="button" @click="cancelAddCategory()">取消</button>
      </div>
    </section>

    <section class="card block">
      <div class="block__title">
        <h2 class="section-title">新建标签</h2>
        <span class="meta">同名会复用已有的那个</span>
      </div>

      <div class="newrow">
        <input
          v-model="newName"
          class="taginput"
          type="text"
          maxlength="24"
          placeholder="标签名，最多 24 字"
          @keyup.enter.prevent="create"
        />
        <button class="btn newrow__btn" type="button" :disabled="creating" @click="create">
          {{ creating ? '建中…' : '添加' }}
        </button>
      </div>

      <div class="palette">
        <button
          v-for="color in TAG_PALETTE"
          :key="color"
          class="swatch"
          :class="{ 'swatch--on': newColor === color }"
          :style="{ background: color }"
          type="button"
          :aria-label="`选择颜色 ${color}`"
          @click="newColor = color"
        />
        <button
          class="swatch swatch--none"
          :class="{ 'swatch--on': newColor === null }"
          type="button"
          aria-label="不设颜色"
          @click="newColor = null"
        >
          无
        </button>
      </div>

      <!-- 归类：分类可能很多，不占一整排 chip，点开选一个就行 -->
      <div class="pickrow">
        <span class="pickrow__label">归类</span>
        <button class="cat cat--pick" type="button" @click="pickTarget = 'new'">
          {{ tagCategoryLabel(newCategory, categories) }}
          <svg
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.6"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="m6 9 6 6 6-6" />
          </svg>
        </button>
      </div>
    </section>

    <section class="card block">
      <div class="block__title">
        <h2 class="section-title">全部标签</h2>
        <span class="meta">
          {{ loading ? '加载中…' : savingOrder ? '顺序保存中…' : `${tags.length} 个` }}
        </span>
      </div>

      <p v-if="!loading && tags.length === 0" class="meta empty">
        还没有标签，上面敲一个就会出现。
      </p>

      <ul v-else ref="listEl" class="tlist">
        <li
          v-for="(tag, index) in tags"
          :key="tag.id"
          class="trow"
          :class="{ 'trow--dragging': draggingId === tag.id }"
          @pointerdown="onRowPressStart($event, index)"
        >
          <template v-if="editingId === tag.id">
            <div class="tedit">
              <input
                :ref="setEditInput"
                v-model="editName"
                class="taginput"
                type="text"
                maxlength="24"
                @keyup.enter.prevent="submitEdit(tag)"
              />
              <div class="palette palette--inline">
                <button
                  v-for="color in TAG_PALETTE"
                  :key="color"
                  class="swatch"
                  :class="{ 'swatch--on': editColor === color }"
                  :style="{ background: color }"
                  type="button"
                  :aria-label="`选择颜色 ${color}`"
                  @click="editColor = color"
                />
                <button
                  class="swatch swatch--none"
                  :class="{ 'swatch--on': editColor === null }"
                  type="button"
                  aria-label="不设颜色"
                  @click="editColor = null"
                >
                  无
                </button>
              </div>
              <div class="tedit__ops">
                <button
                  class="op op--primary"
                  type="button"
                  :disabled="savingId === tag.id"
                  @click="submitEdit(tag)"
                >
                  {{ savingId === tag.id ? '保存中…' : '保存' }}
                </button>
                <button class="op" type="button" @click="cancelEdit()">取消</button>
              </div>
            </div>
          </template>

          <template v-else>
            <span class="tdot" :style="{ background: resolveTagColor(tag) }" />
            <span class="tname">{{ tag.name }}</span>
            <span class="tcat">{{ tagCategoryLabel(tag.category, categories) }}</span>

            <!-- 上下移：拖拽的替代入口，一下挪一格。首项禁用↑、末项禁用↓，避免「点了没反应」 -->
            <div class="tmove">
              <button
                class="tmove__btn"
                type="button"
                :disabled="index === 0"
                aria-label="上移"
                @click="moveTag(index, -1)"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <path d="m6 14 6-6 6 6" />
                </svg>
              </button>
              <button
                class="tmove__btn"
                type="button"
                :disabled="index === tags.length - 1"
                aria-label="下移"
                @click="moveTag(index, 1)"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <path d="m6 10 6 6 6-6" />
                </svg>
              </button>
            </div>

            <button
              class="op op--icon"
              type="button"
              aria-label="更多操作"
              @click="openRowSheet(tag)"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <circle cx="5" cy="12" r="1.7" />
                <circle cx="12" cy="12" r="1.7" />
                <circle cx="19" cy="12" r="1.7" />
              </svg>
            </button>
          </template>
        </li>
      </ul>
    </section>

    <ActionSheet
      :visible="!!rowSheet"
      :actions="rowActions"
      @select="onRowAction"
      @close="rowSheet = null"
    />
    <ActionSheet
      :visible="!!pickTarget"
      :actions="pickActions"
      @select="onPickCategory"
      @close="pickTarget = null"
    />
    <ActionSheet
      :visible="!!typeSheet"
      :actions="typeActions"
      @select="onTypeAction"
      @close="typeSheet = null"
    />
  </div>
</template>

<style scoped>
.head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.head__back {
  flex: none;
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 50%;
  background: var(--card);
  box-shadow: var(--sh);
  color: var(--ink);
  font-size: 17px;
  line-height: 1;
  cursor: pointer;
}

.head__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.block {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}

.block__title {
  display: flex;
  gap: 10px;
  align-items: baseline;
  justify-content: space-between;
}

/* ---- 新建 ---- */
.newrow {
  display: flex;
  gap: 8px;
  align-items: center;
}

.taginput {
  flex: 1;
  min-width: 0;
  height: 44px;
  padding: 0 12px;
  border: 0;
  border-radius: var(--r);
  background: var(--bg);
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 600;
}

.taginput:focus {
  outline: 2px solid var(--ink);
  outline-offset: -2px;
}

.newrow__btn {
  flex: none;
  height: 44px;
  padding: 0 18px;
}

/* ---- 色板：10 个预设 + 一个「无」 ---- */
.palette {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  align-items: center;
}

.palette--inline {
  margin: 2px 0 0;
}

/* 24px + 7px 间距：412 宽的机器上 10 色 + 「无」正好一行放完，不折行 */
.swatch {
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
}

/* 选中态用「外圈墨线」而不是再加一层阴影：色块本身的颜色要能看清 */
.swatch--on {
  box-shadow: 0 0 0 2px var(--card), 0 0 0 4px var(--ink);
}

.swatch--none {
  display: flex;
  align-items: center;
  justify-content: center;
  width: auto;
  padding: 0 9px;
  border-radius: 999px;
  background: repeating-linear-gradient(45deg, #fff 0 3px, #e6e8eb 3px 6px);
  color: var(--ink2);
  font-family: inherit;
  font-size: 11px;
  font-weight: 700;
}

/* ---- 类型 chips：浅底小胶囊，后缀 × 是删除，横排换行 ---- */
.cats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/* 整颗胶囊都是按钮：点开就是【重命名 / 上移 / 下移 / 删除】，
   不在胶囊里塞小小的 ×，一是不好按，二是标签页已经有「点开选项」的肌肉记忆 */
.cat {
  display: inline-flex;
  gap: 2px;
  align-items: center;
  height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: 999px;
  background: var(--bg);
  color: var(--ink2);
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: background 0.12s ease, color 0.12s ease;
}

.cat:hover {
  background: rgba(17, 19, 24, 0.07);
  color: var(--ink);
}

.cat--add {
  border: 1px dashed var(--line);
  background: transparent;
  color: var(--ink3);
  cursor: pointer;
}

/* 新标签的归类触发器：墨底白字，明确「这是一个可点的选择器」 */
.cat--pick {
  gap: 4px;
  height: 32px;
  background: var(--ink);
  color: #fff;
  cursor: pointer;
}

.pickrow {
  display: flex;
  gap: 10px;
  align-items: center;
}

.pickrow__label {
  color: var(--ink3);
  font-size: 12px;
  font-weight: 600;
}

/* ---- 标签行 ---- */
.tlist {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  list-style: none;
}

.trow {
  display: flex;
  gap: 8px;
  align-items: center;
  min-height: 44px;
  border-top: 1px solid var(--line);
  /* 长按拖拽：别让系统弹文字选择 / 上下文菜单。
     这里刻意不写 touch-action: pan-y —— 那等于告诉浏览器「纵向移动归你」，
     浏览器一开始滚动就会发 pointercancel 掐掉拖拽。滚动改由拖拽中的 touchmove 拦截控制 */
  user-select: none;
  -webkit-touch-callout: none;
}

/* 拖拽中的行「抬起来」：白底 + 阴影 + 轻微放大，明确它在跟手 */
.trow--dragging {
  position: relative;
  z-index: 2;
  border-color: transparent;
  border-radius: 10px;
  background: var(--card);
  box-shadow: 0 12px 26px -14px rgba(17, 19, 24, 0.45);
  transform: scale(1.01);
}

.trow:first-child {
  border-top: 0;
}

.tdot {
  flex: none;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.tname {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 类型：浅底小胶囊，比名字弱一档 */
.tcat {
  flex: none;
  padding: 2px 6px;
  border-radius: 999px;
  background: var(--bg);
  color: var(--ink3);
  font-size: 10.5px;
  font-weight: 600;
  white-space: nowrap;
}

/* 上下移：无底色的纯图标按钮，跟右侧「⋯」区分开，
   一排里不会看着像两个同等重要的按钮 */
.tmove {
  display: flex;
  flex: none;
  /* 4px 间距：两个箭头挨太近容易误触，手不灵活时更明显 */
  gap: 4px;
}

.tmove__btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 32px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink2);
  cursor: pointer;
  transition: background 0.12s ease, color 0.12s ease;
}

.tmove__btn:hover:not(:disabled) {
  background: var(--bg);
  color: var(--ink);
}

.tmove__btn:disabled {
  opacity: 0.28;
  cursor: default;
}

.op {
  flex: none;
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  background: var(--bg);
  color: var(--ink2);
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.op:disabled {
  opacity: 0.45;
  cursor: default;
}

/* ⋯：无底色，跟上下移一致；三个操作都藏在它后面 */
.op--icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  padding: 0;
  background: transparent;
  color: var(--ink2);
}

.op--icon:hover {
  background: var(--bg);
  color: var(--ink);
}

.op--primary {
  background: var(--ink);
  color: #fff;
}

/* ---- 行内编辑 ---- */
.tedit {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  padding: 10px 0;
}

.tedit__ops {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.empty {
  padding: 6px 0;
}
</style>
