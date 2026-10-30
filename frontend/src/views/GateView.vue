<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, markAuthenticated } from '@/api/auth'
import { HttpError } from '@/api/http'

/**
 * 口令门。原型：designs/打卡地图/选稿/扫街榜/gate.png
 *
 * 与原型的两处刻意差异：
 *   ① 顶部没有照片墙 —— 图片管道（上传 + CDN）还没做，先不塞占位图
 *   ② 底部「142 打卡点 / 87 到访 / 4 城市」也先不放 —— 那要等 spot CRUD 与统计接口
 * 其余（标题橙色「想去」、54px 输入、黑底 48px 主按钮、限流提示）照原型来。
 */
const route = useRoute()
const router = useRouter()

const password = ref('')
const errorMessage = ref('')
const submitting = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

async function submit(): Promise<void> {
  if (submitting.value) {
    return
  }
  if (!password.value) {
    errorMessage.value = '请输入口令'
    inputRef.value?.focus()
    return
  }

  errorMessage.value = ''
  submitting.value = true
  try {
    // 口令不做 trim：首尾空格也可能是口令的一部分
    await login(password.value)
    markAuthenticated()
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error) {
    errorMessage.value = error instanceof HttpError ? error.message : '登录失败，请重试'
    password.value = ''
    inputRef.value?.focus()
  } finally {
    submitting.value = false
  }
}

onMounted(() => inputRef.value?.focus())
</script>

<template>
  <div class="gate">
    <section class="card gate__card">
      <p class="meta gate__eyebrow">SEAN · 私人地图</p>

      <h1 class="gate__title">
        我去过的<br />
        和<span class="gate__hot">想去</span>的
      </h1>

      <p class="meta gate__desc">只记录我自己走过的</p>

      <form class="gate__form" @submit.prevent="submit">
        <label class="gate__label" for="gate-password">口令</label>
        <input
          id="gate-password"
          ref="inputRef"
          v-model="password"
          class="gate__input"
          type="password"
          inputmode="text"
          autocomplete="current-password"
          placeholder="输入口令"
          :disabled="submitting"
        />

        <p v-if="errorMessage" class="gate__error">{{ errorMessage }}</p>

        <button class="btn gate__submit" type="submit" :disabled="submitting">
          {{ submitting ? '校验中…' : '进入' }}
        </button>
      </form>

      <p class="meta gate__hint">连续错 5 次锁 10 分钟 · 只此一人使用</p>

      <div class="gate__foot">
        <p class="meta">没有账号，也没有注册。</p>
        <p class="meta">口令只在服务器上校验一次，会话有期限，随时可清。</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.gate {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px 16px;
}

.gate__card {
  width: 100%;
  max-width: 420px;
  padding: 24px 20px 22px;
  /* 原型是三档面板的圆角语言，口令门这张卡用更大的圆角，视觉上更像"入口" */
  border-radius: 20px;
}

.gate__eyebrow {
  margin: 0 0 10px;
}

.gate__title {
  margin: 0;
  font-size: 30px;
  font-weight: 800;
  line-height: 1.15;
  letter-spacing: -0.045em;
}

.gate__hot {
  color: var(--hot);
}

.gate__desc {
  margin: 12px 0 0;
}

.gate__form {
  margin-top: 26px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.gate__label {
  font-size: 11px;
  font-weight: 500;
  color: var(--ink3);
}

/* 输入框在白色卡片上必须靠底色区分，白底白框等于没有边界 */
.gate__input {
  height: 54px;
  padding: 0 14px;
  border: 0;
  border-radius: var(--r);
  background: var(--bg);
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 500;
  outline: none;
}

.gate__input::placeholder {
  color: var(--ink3);
}

.gate__input:focus {
  box-shadow: 0 0 0 1.5px var(--ink);
}

.gate__error {
  margin: 0;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--hot);
}

.gate__submit {
  margin-top: 6px;
  width: 100%;
}

.gate__hint {
  margin: 12px 0 0;
  text-align: center;
}

.gate__foot {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.gate__foot p {
  margin: 0;
}
</style>
