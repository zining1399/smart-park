<template>
  <!-- 按需引入 Element Plus 后，中文语言包通过 el-config-provider 下发（替代全量 app.use(ElementPlus)） -->
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>
