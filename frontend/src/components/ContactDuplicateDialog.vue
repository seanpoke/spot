<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ActionSheet from '@/components/ActionSheet.vue'
import { contactMeta } from '@/config/contacts'
import type { ContactDuplicate } from '@/api/spots'

/**
 * 联系方式查重结果弹窗。
 *
 * 两个入口共用：
 *   - 编辑页联系方式卡上的「查重」→ mode='check'：没重复也弹，显示「无重复联系方式 √」
 *   - 点保存时自动查 → mode='save'：只在这时候才有「仍然保存」这个选项
 *
 * 冲突行按需求写成「微信【x】与用户【张三】的微信重复」，用户名是蓝色按钮：
 * 点开底部选项框，只有「复制」（复制用户名）与「跳转」（去那个打卡点详情）两条。
 *
 * 跳转带上 `?back=`：详情页返回时按它回到这一页，而不是回到列表/首页 ——
 * 「从哪来回哪去」，而且这里可能是编辑到一半的页面。
 */
const props = withDefaults(
  defineProps<{
    visible: boolean
    duplicates: ContactDuplicate[]
    /** save：从保存流程进来，多一个「仍然保存」；check：只看结果 */
    mode?: 'check' | 'save'
  }>(),
  { mode: 'check' },
)

const emit = defineEmits<{ close: []; confirm: [] }>()

const route = useRoute()
const router = useRouter()

const hasDuplicate = computed(() => props.duplicates.length > 0)

const sheetItem = ref<ContactDuplicate | null>(null)
const sheetActions = [
  { key: 'copy', label: '复制' },
  { key: 'go', label: '跳转' },
]

function labelOf(type: string): string {
  return contactMeta(type).label
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    if (sheetItem.value) {
      sheetItem.value = null
      return
    }
    emit('close')
  }
}

watch(
  () => props.visible,
  (open) => {
    if (open) {
      document.body.style.overflow = 'hidden'
      window.addEventListener('keydown', onKeydown)
    } else {
      sheetItem.value = null
      document.body.style.overflow = ''
      window.removeEventListener('keydown', onKeydown)
    }
  },
)

// ActionSheet 关闭时会把 body 的 overflow 复位，但弹窗还开着 —— 这里补回来
watch(sheetItem, (item) => {
  if (!item && props.visible) {
    document.body.style.overflow = 'hidden'
  }
})

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})

async function copyName(name: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(name)
    ElMessage.success(`已复制「${name}」`)
  } catch {
    ElMessage.error('复制失败，请长按文字手动选择')
  }
}

function jumpTo(item: ContactDuplicate): void {
  // 先关弹窗：从详情页返回时不该再看到上一次的结果
  emit('close')
  void router.push({
    path: `/spot/${item.otherSpotId}`,
    query: { back: route.fullPath },
  })
}

async function onSheetSelect(key: string): Promise<void> {
  const item = sheetItem.value
  sheetItem.value = null
  if (!item) {
    return
  }
  if (key === 'copy') {
    await copyName(item.otherSpotName)
    return
  }
  if (key === 'go') {
    jumpTo(item)
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="dup" role="dialog" aria-modal="true" aria-label="联系方式查重">
      <button class="dup__mask" type="button" aria-label="关闭" @click="emit('close')" />

      <div class="dup__card">
        <h2 class="dup__title">
          {{ hasDuplicate ? '以下联系方式可能重复' : '无重复联系方式' }}
        </h2>

        <p v-if="!hasDuplicate" class="dup__desc">
          同类型（微信比微信、电话比电话）和其它打卡点都没有撞上。
        </p>

        <ul v-else class="dup__list">
          <li v-for="(item, index) in duplicates" :key="`${item.otherSpotId}-${index}`" class="dup__item">
            <span class="dup__text">{{ labelOf(item.type) }}【{{ item.value }}】与用户</span>
            <button class="dup__user" type="button" @click="sheetItem = item">
              【{{ item.otherSpotName }}】
            </button>
            <span class="dup__text">的{{ labelOf(item.type) }}重复</span>
          </li>
        </ul>

        <p v-if="hasDuplicate && mode === 'save'" class="dup__hint">
          确认无误的话可以照常保存，这里只是提醒。
        </p>

        <div class="dup__ops">
          <button
            v-if="hasDuplicate && mode === 'save'"
            class="btn btn--ghost dup__btn"
            type="button"
            @click="emit('close')"
          >
            返回修改
          </button>
          <button
            v-if="hasDuplicate && mode === 'save'"
            class="btn dup__btn"
            type="button"
            @click="emit('confirm')"
          >
            仍然保存
          </button>
          <button v-else class="btn dup__btn" type="button" @click="emit('close')">知道了</button>
        </div>
      </div>
    </div>

    <ActionSheet
      :visible="!!sheetItem"
      :actions="sheetActions"
      @select="onSheetSelect"
      @close="sheetItem = null"
    />
  </Teleport>
</template>

<style scoped>
.dup {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
}

.dup__mask {
  position: absolute;
  inset: 0;
  width: 100%;
  padding: 0;
  border: 0;
  background: rgba(17, 19, 24, 0.42);
  animation: dup-fade 0.16s ease-out;
  cursor: default;
}

@keyframes dup-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}

.dup__card {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 420px;
  max-height: 80dvh;
  padding: 22px 20px 16px;
  border-radius: 18px;
  background: var(--card);
  box-shadow: 0 24px 60px -30px rgba(17, 19, 24, 0.55);
  animation: dup-pop 0.18s cubic-bezier(0.2, 0.8, 0.3, 1);
}

@keyframes dup-pop {
  from { transform: translateY(10px) scale(0.98); opacity: 0; }
  to { transform: translateY(0) scale(1); opacity: 1; }
}

/* 标题：有冲突时直接一句话说清，不配图标 —— 警示靠橙色文字标题足够，
   加个大感叹号反而像报错，这里只是提醒 */
.dup__title {
  margin: 0 0 6px;
  font-size: 17px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.dup__desc {
  margin: 0 0 16px;
  color: var(--ink3);
  font-size: 12.5px;
  line-height: 1.6;
}

.dup__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0 0 14px;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.dup__item {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  align-items: baseline;
  line-height: 1.65;
}

.dup__text {
  color: var(--ink2);
  font-size: 13px;
}

/* 用户名：蓝色可点。这是全站唯一的蓝色文字，只为「这里能点」服务，别扩散到别处 */
.dup__user {
  padding: 0;
  border: 0;
  background: none;
  color: #1f6feb;
  font-family: inherit;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  text-decoration: underline;
  text-decoration-thickness: 1px;
  text-underline-offset: 2px;
}

.dup__hint {
  margin: 0 0 12px;
  color: var(--ink3);
  font-size: 12px;
}

.dup__ops {
  display: flex;
  gap: 10px;
  margin-top: auto;
}

.dup__btn {
  flex: 1;
  height: 46px;
}
</style>
