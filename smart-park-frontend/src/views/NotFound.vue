<template>
  <div class="not-found">
    <el-result :icon="isForbidden ? 'warning' : 'info'" :title="title" :sub-title="subTitle">
      <template #extra>
        <el-button type="primary" @click="goHome">返回工作台</el-button>
        <el-button @click="goBack">返回上一页</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// /403 无权限 与 /:pathMatch(.*)* 路径不存在 复用同一页面，用 query 区分
const isForbidden = computed(() => route.path === '/403')
const title = computed(() => (isForbidden.value ? '403 无访问权限' : '404 页面不存在'))
const subTitle = computed(() =>
  isForbidden.value
    ? `当前账号角色为「${auth.roleLabel || '未知'}」，没有访问该页面的权限，请联系管理员`
    : '访问的页面地址不存在或已被移除'
)

function goHome() {
  router.replace('/dashboard')
}
function goBack() {
  router.back()
}
</script>

<style scoped>
.not-found {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
