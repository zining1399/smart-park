<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-title">
        <el-icon :size="26" color="#409EFF"><OfficeBuilding /></el-icon>
        <span>{{ appTitle }}</span>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" :prefix-icon="User" placeholder="请输入账号" autocomplete="username" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            :prefix-icon="Lock"
            placeholder="请输入密码"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="submitting" @click="handleSubmit">登 录</el-button>
      </el-form>

      <div class="login-tip">请使用园区分配的账号登录 · 登录失败会提示具体原因</div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Lock, OfficeBuilding } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { login } from '../api/auth'

const appTitle = import.meta.env.VITE_APP_TITLE || '智慧园区管理系统'
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref(null)
const submitting = ref(false)
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleSubmit() {
  // 校验失败静默返回：validate() 会 reject，不 catch 会产生 unhandled rejection
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    // 40102/40103 等错误由 axios 拦截器统一提示，此处只处理成功分支
    const data = await login({ username: form.username.trim(), password: form.password })
    auth.setAuth(data)
    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/dashboard')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #001529 0%, #0b3d6b 100%);
}

.login-card {
  width: 380px;
  border-radius: 10px;
}

.login-title {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 19px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 24px;
}

.login-btn {
  width: 100%;
}

.login-tip {
  margin-top: 16px;
  font-size: 12px;
  color: #909399;
  text-align: center;
}
</style>
