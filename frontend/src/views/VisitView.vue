<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import RankPicker from '@/components/RankPicker.vue'
import SafeImage from '@/components/SafeImage.vue'
import { getSpot, type SpotDetail, type PhotoRef } from '@/api/spots'
import {
  createVisit,
  deleteVisit,
  getVisit,
  updateVisit,
  type ScoreItem,
  type VisitItem,
  type VisitSaveRequest,
} from '@/api/visits'
import { uploadImage } from '@/api/upload'
import { compressImage } from '@/utils/image'
import { HttpError } from '@/api/http'
import { SCORE_DIMENSIONS, type ScoreKey } from '@/config/scoreDimensions'

/**
 * 记一次到访（原型 `designs/打卡地图/选稿/扫街榜/visit.png`）。
 *
 * **段位语义（PRD 5.1，最容易做错的地方）**
 *   - 在这里选的段位 = 手动定级，保存后回写 `spot.rank_level` 并锁定 `rank_manual=1`
 *   - 一步没动过 → 提交 null，交给后端在「没锁过」时按新均分更新建议值
 *   - 0 次到访本来就没有建议值，但**绝不禁止选**：段位是「我推荐它到什么程度」，
 *     可以来自听说、朋友推荐，没去过照样能定王者
 *
 * 分项评分只提交 `config/scoreDimensions.ts` 里定义的项，后端不关心结构。
 * 照片等上传接口做好后再接 `visit.photos`。
 */
const route = useRoute()
const router = useRouter()

/** 编辑模式走 `/visit/:id/edit`，新建走 `/spot/:id/visit/new`，同一个页面两种职责 */
const visitId = computed(() => (route.name === 'visit-edit' ? String(route.params.id) : null))
const isEdit = computed(() => visitId.value !== null)

/** 两种模式下都会在 load 时确定：新建取路由参数，编辑取记录自带的 spotId */
const spotId = ref('')
const spot = ref<SpotDetail | null>(null)
const loading = ref(true)
const saving = ref(false)
const deleting = ref(false)
const uploading = ref('')
const errorText = ref('')

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 到访日期只到天。用本地时间拼，不要 toISOString —— 那会按 UTC 偏移掉一天 */
function todayIso(): string {
  const now = new Date()
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const form = reactive({
  visitedAt: todayIso(),
  rating: 4,
  rankLevel: 0,
  scores: {} as Record<string, number | null>,
  /** 数字框可能给 number，也可能给字符串，统一用 parseNumber 收口 */
  cost: '' as string | number,
  companions: '',
  note: '',
  photos: [] as PhotoRef[],
})

for (const dimension of SCORE_DIMENSIONS) {
  form.scores[dimension.key] = null
}

/** 载入时的段位，用来判断「用户到底动没动」——没动就不该锁定段位 */
const originalRank = ref(0)

const headerDate = computed(() => {
  if (!form.visitedAt) return ''
  const date = new Date(`${form.visitedAt}T00:00:00`)
  return `${date.getFullYear()} 年 ${date.getMonth() + 1} 月 ${date.getDate()} 日 ${WEEK[date.getDay()]}`
})

const historyLine = computed(() => {
  const current = spot.value
  if (!current) return ''
  if (current.visitCount === 0) return '第一次来'
  const next = current.visitCount + 1
  if (!current.lastVisitAt) return `第 ${next} 次`
  const [, month, day] = current.lastVisitAt.split('-')
  return `第 ${next} 次 · 上次来是 ${Number(month)}.${Number(day)}`
})

function messageOf(error: unknown): string {
  return error instanceof HttpError ? error.message : '保存失败，请重试'
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

function parseNumber(value: string | number): number | null {
  const raw = typeof value === 'number' ? value : value.trim()
  if (raw === '') return null
  const parsed = Number(raw)
  return Number.isFinite(parsed) ? parsed : null
}

/** 点第 n 段 = 打 n 分；再点同一段 = 清成 N/A（这一项不适用） */
function setScore(key: ScoreKey, value: number): void {
  form.scores[key] = form.scores[key] === value ? null : value
}

function scoreText(key: ScoreKey): string {
  const value = form.scores[key]
  return value === null || value === undefined ? 'N/A' : String(value)
}

function buildScores(): ScoreItem[] {
  return SCORE_DIMENSIONS.map((dimension) => ({
    key: dimension.key,
    label: dimension.label,
    value: form.scores[dimension.key] ?? null,
  }))
}

/** 把已存在的记录填回表单（编辑模式） */
function fillForm(visit: VisitItem): void {
  form.visitedAt = visit.visitedAt
  form.rating = visit.rating
  for (const dimension of SCORE_DIMENSIONS) {
    const item = (visit.scores ?? []).find((score) => score.key === dimension.key)
    // 不适用项在 JSON 里整个字段被省掉 → 只能按 typeof 判断
    form.scores[dimension.key] = typeof item?.value === 'number' ? item.value : null
  }
  form.cost = visit.cost === undefined ? '' : String(visit.cost)
  form.companions = visit.companions ?? ''
  form.note = visit.note ?? ''
  form.photos = visit.photos ?? []
}

function resetForm(): void {
  form.visitedAt = todayIso()
  form.rating = 4
  form.cost = ''
  form.companions = ''
  form.note = ''
  form.photos = []
  for (const dimension of SCORE_DIMENSIONS) {
    form.scores[dimension.key] = null
  }
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  spot.value = null
  try {
    if (visitId.value) {
      const visit = await getVisit(visitId.value)
      spotId.value = visit.spotId
      fillForm(visit)
      spot.value = await getSpot(visit.spotId)
    } else {
      spotId.value = String(route.params.id)
      spot.value = await getSpot(spotId.value)
    }
    // 有建议值就先落在建议值上，没有就用当前段位 —— 用户随时可以改
    form.rankLevel = spot.value.suggestedRankLevel ?? spot.value.rankLevel
    originalRank.value = form.rankLevel
  } catch (error) {
    errorText.value = error instanceof HttpError ? error.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

function goBack(): void {
  router.replace(spotId.value ? `/spot/${spotId.value}` : '/')
}

async function remove(): Promise<void> {
  if (!visitId.value) return
  try {
    await ElMessageBox.confirm('删掉这条到访记录？删除后不能恢复。', '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  deleting.value = true
  try {
    await deleteVisit(visitId.value)
    ElMessage.success('已删除')
    await router.replace(`/spot/${spotId.value}`)
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    deleting.value = false
  }
}

/** 选择并上传这条到访的照片（最多 9 张，存 photo 表 owner_type='visit'） */
async function addPhotos(): Promise<void> {
  if (uploading.value) return
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.multiple = true
  input.onchange = async () => {
    const files = Array.from(input.files ?? []).slice(0, 9 - form.photos.length)
    if (files.length === 0) return
    uploading.value = 'photos'
    try {
      for (const file of files) {
        const uploaded = await uploadImage(await compressImage(file))
        form.photos.push({
          id: uploaded.id,
          large: uploaded.large,
          thumb: uploaded.thumb,
          width: uploaded.width,
          height: uploaded.height,
          size: uploaded.size,
        })
      }
      ElMessage.success('照片已上传，记得点保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '上传失败，请重试')
    } finally {
      uploading.value = ''
    }
  }
  input.click()
}

function removePhoto(index: number): void {
  form.photos.splice(index, 1)
}

async function save(): Promise<void> {
  if (saving.value) return
  if (!form.visitedAt) {
    ElMessage.warning('先选一下到访日期')
    return
  }
  saving.value = true
  try {
    const payload: VisitSaveRequest = {
      visitedAt: form.visitedAt,
      rating: form.rating,
      scores: buildScores(),
      cost: parseNumber(form.cost),
      companions: emptyToNull(form.companions),
      note: emptyToNull(form.note),
      photos: form.photos.map((photo) => ({
        id: photo.id,
        large: photo.large,
        thumb: photo.thumb,
        width: photo.width,
        height: photo.height,
        size: photo.size,
      })),
      // 没动过就交 null，别把「用户没表态」变成「手动锁定段位」
      rankLevel: form.rankLevel === originalRank.value ? null : form.rankLevel,
    }
    if (visitId.value) {
      await updateVisit(visitId.value, payload)
      ElMessage.success('已更新这次到访')
    } else {
      await createVisit(spotId.value, payload)
      ElMessage.success('已记录这次到访')
    }
    await router.replace(`/spot/${spotId.value}`)
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    saving.value = false
  }
}

// 新建与编辑是同一个组件：靠 fullPath 变化区分（从一条记录跳到另一条也要重载）
watch(
  () => route.fullPath,
  () => {
    resetForm()
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="page visit">
    <div v-if="errorText" class="visit__state">
      <p class="visit__error">{{ errorText }}</p>
      <button class="btn btn--ghost" type="button" @click="goBack()">返回详情</button>
    </div>

    <template v-else-if="!loading && spot">
      <header class="visit__head">
        <button class="icon-btn" type="button" aria-label="返回" @click="goBack()">←</button>
        <div class="visit__head-text">
          <h1 class="page-title">{{ isEdit ? '改这次到访' : '记一次到访' }}</h1>
          <p class="meta">{{ headerDate }}</p>
        </div>
      </header>

      <section class="card spot-line">
        <SafeImage class="spot-line__avatar" :src="spot.avatar" kind="avatar" :alt="spot.name" />
        <div class="spot-line__text">
          <strong class="spot-line__name">{{ spot.name }}</strong>
          <span class="meta">{{ historyLine }}</span>
        </div>
      </section>

      <section class="card block">
        <div class="row">
          <span class="row__label">日期</span>
          <div class="date-wrap">
            <el-date-picker
              v-model="form.visitedAt"
              class="date-picker"
              type="date"
              value-format="YYYY-MM-DD"
              format="M 月 D 日"
              popper-class="visit-date-popper"
              :clearable="false"
            />
          </div>
          <span class="row__chev" aria-hidden="true" />
        </div>
      </section>

      <section class="card block">
        <div class="rating">
          <strong class="num rating__num">{{ form.rating }}</strong>
          <div class="stars">
            <button
              v-for="n in 5"
              :key="n"
              type="button"
              class="star"
              :class="{ 'star--on': n <= form.rating }"
              :aria-label="`${n} 星`"
              @click="form.rating = n"
            >
              ★
            </button>
          </div>
        </div>
      </section>

      <div class="block__title block__title--outside">
        <h2 class="section-title">这次给什么段位</h2>
        <span class="meta">段位越高，刻度条越长</span>
      </div>
      <section class="card block">
        <RankPicker v-model="form.rankLevel" />
      </section>

      <div class="block__title block__title--outside">
        <h2 class="section-title">分项评分</h2>
        <span class="meta">不适用就再点一次取消</span>
      </div>
      <section class="card block">
        <div v-for="dimension in SCORE_DIMENSIONS" :key="dimension.key" class="score-row">
          <span class="score-row__label" :title="dimension.hint">{{ dimension.label }}</span>
          <div class="score-row__bars">
            <button
              v-for="n in 5"
              :key="n"
              type="button"
              class="score-bar"
              :class="{ 'score-bar--on': (form.scores[dimension.key] ?? 0) >= n }"
              :aria-label="`${dimension.label} ${n} 分`"
              @click="setScore(dimension.key, n)"
            />
          </div>
          <span
            class="score-row__value"
            :class="{ 'score-row__value--na': form.scores[dimension.key] == null }"
          >
            {{ scoreText(dimension.key) }}
          </span>
        </div>
      </section>

      <div class="block__title block__title--outside">
        <h2 class="section-title">花费</h2>
      </div>
      <section class="card block">
        <label class="row">
          <span class="row__label">这一顿</span>
          <input v-model="form.cost" class="row__input" type="number" step="0.01" placeholder="可空" />
          <span class="row__unit">元</span>
        </label>
        <label class="row">
          <span class="row__label">同行人</span>
          <input v-model="form.companions" class="row__input" type="text" maxlength="120" placeholder="可空" />
        </label>
      </section>

      <div class="block__title block__title--outside">
        <h2 class="section-title">一句话感受</h2>
        <span class="meta">可以留空</span>
      </div>
      <section class="card block">
        <textarea
          v-model="form.note"
          class="textarea"
          rows="3"
          maxlength="500"
          placeholder="印象最深的一点…"
        />
      </section>

      <div class="block__title block__title--outside">
        <h2 class="section-title">照片</h2>
        <span class="meta">这条到访的照片，最多 9 张</span>
      </div>
      <section class="card block">
        <div class="photogrid">
          <div v-for="(photo, index) in form.photos" :key="photo.id" class="photogrid__item">
            <SafeImage :src="photo.thumb" :alt="`照片 ${index + 1}`" />
            <button type="button" class="photogrid__del" :aria-label="`删除第 ${index + 1} 张`" @click="removePhoto(index)">×</button>
          </div>
          <button
            v-if="form.photos.length < 9"
            type="button"
            class="photogrid__add"
            :disabled="!!uploading"
            @click="addPhotos"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            <span>{{ uploading ? '上传中…' : '添加照片' }}</span>
          </button>
        </div>
      </section>

      <footer class="visit__actions">
        <button
          v-if="isEdit"
          class="btn btn--ghost visit__remove"
          type="button"
          :disabled="saving || deleting"
          @click="remove"
        >
          删除
        </button>
        <button class="btn visit__save" type="button" :disabled="saving || deleting" @click="save">
          {{ saving ? '保存中…' : isEdit ? '保存修改' : '保存这次到访' }}
        </button>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.visit {
  /* 给底部固定保存栏留位置 */
  padding-bottom: 92px;
}

.visit__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-height: 60vh;
}

.visit__error {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--ink2);
}

.visit__head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.visit__head-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.visit__head-text p {
  margin: 0;
}

.icon-btn {
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

/* ---- 打卡点身份条 ---- */
.spot-line {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
}

.spot-line__avatar {
  flex: none;
  width: 42px;
  height: 42px;
  border-radius: 10px;
  object-fit: cover;
}

.spot-line__avatar--text {
  display: grid;
  place-items: center;
  background: var(--bg);
  color: var(--ink2);
  font-size: 15px;
  font-weight: 700;
}

.spot-line__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.spot-line__name {
  font-size: 15px;
  font-weight: 800;
  letter-spacing: -0.02em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- 通用块 ---- */
.block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}

.block__title--outside {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin: 4px 0 -6px;
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 46px;
}

.row + .row {
  border-top: 1px solid var(--line);
}

.row__label {
  flex: none;
  min-width: 64px;
  font-size: 13px;
  color: var(--ink2);
}

.row__input {
  flex: 1;
  min-width: 0;
  height: 46px;
  border: 0;
  background: transparent;
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 600;
  text-align: right;
  outline: none;
}

.row__input::placeholder {
  color: var(--ink3);
  font-weight: 500;
}

.row__unit {
  flex: none;
  font-size: 13px;
  color: var(--ink3);
}

/* EP 的日期控件要拆掉默认的输入框外壳：换肤后的描边在这条 46px 行里像另一个控件 */
.date-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  justify-content: flex-end;
}

.date-picker {
  width: 100%;
}

.date-wrap :deep(.el-input__wrapper),
.date-wrap :deep(.el-input__wrapper.is-focus),
.date-wrap :deep(.el-input__wrapper:hover) {
  box-shadow: none;
  background: transparent;
  padding: 0;
}

.date-wrap :deep(.el-input__inner) {
  text-align: right;
  font-weight: 700;
  color: var(--ink);
}

/* EP 把日历图标放在 input 的前缀里，46px 的行会被撕成「日期 …图标… 值」三段。
   图标去掉，右侧补一个箭头表示「点了会弹面板」 */
.date-wrap :deep(.el-input__prefix) {
  display: none;
}

.row__chev {
  flex: none;
  width: 7px;
  height: 7px;
  border-top: 1.6px solid #c8ccd2;
  border-right: 1.6px solid #c8ccd2;
  transform: rotate(45deg);
}

/* ---- 整体评分 ---- */
.rating {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.rating__num {
  font-size: 38px;
  line-height: 1.1;
}

.stars {
  display: flex;
  gap: 4px;
}

.star {
  width: 34px;
  height: 34px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--line2);
  font-size: 26px;
  line-height: 1;
  cursor: pointer;
}

.star--on {
  color: var(--ink);
}

/* ---- 分项评分 ---- */
.score-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 48px;
}

.score-row + .score-row {
  border-top: 1px solid var(--line);
}

.score-row__label {
  flex: none;
  min-width: 56px;
  font-size: 13px;
  color: var(--ink2);
}

.score-row__bars {
  flex: 1;
  display: flex;
  gap: 4px;
}

/* 药丸形：与原型 visit.png 的刻度条一致 */
.score-bar {
  flex: 1;
  height: 9px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: var(--bar-empty);
  cursor: pointer;
}

.score-bar--on {
  background: var(--ink);
}

.score-row__value {
  flex: none;
  width: 34px;
  text-align: right;
  font-size: 13px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}

.score-row__value--na {
  font-weight: 600;
  color: var(--ink3);
}

/* ---- 感受 ---- */
.textarea {
  width: 100%;
  padding: 12px;
  border: 0;
  border-radius: var(--r);
  background: var(--bg);
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 500;
  line-height: 1.6;
  resize: vertical;
  outline: none;
}

/* ---- 照片九宫格 ---- */
.photogrid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.photogrid__item {
  position: relative;
  aspect-ratio: 1 / 1;
  border-radius: 10px;
  overflow: hidden;
  background: var(--bg);
}

.photogrid__item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.photogrid__del {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 50%;
  background: rgba(17, 19, 24, 0.62);
  color: #fff;
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}

.photogrid__add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  aspect-ratio: 1 / 1;
  border: 0;
  border-radius: 10px;
  background: var(--bg);
  box-shadow: 0 0 0 1px var(--line2) inset;
  color: var(--ink3);
  font-family: inherit;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}

/* ---- 底部保存 ---- */
.visit__actions {
  position: fixed;
  left: 50%;
  bottom: 0;
  transform: translateX(-50%);
  width: 100%;
  max-width: 720px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  background: var(--card);
  box-shadow: 0 -1px 0 var(--line);
  display: flex;
  gap: 10px;
}

.visit__save {
  flex: 1;
}

.visit__remove {
  flex: none;
  padding: 0 22px;
}
</style>
