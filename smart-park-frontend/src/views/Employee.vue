<template>
  <div class="page-card">
    <!-- 条件搜索 -->
    <el-form :inline="true" :model="query" class="search-bar">
      <el-form-item label="姓名">
        <el-input v-model="query.name" placeholder="姓名" clearable style="width: 140px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="部门">
        <el-input v-model="query.dept" placeholder="部门" clearable style="width: 140px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
          <el-option label="在职" value="在职" />
          <el-option label="离职" value="离职" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-toolbar">
      <span></span>
      <el-button v-permission="['ADMIN']" type="primary" @click="openDialog()">新增员工</el-button>
    </div>

    <!-- 分页表格 -->
    <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" style="margin-bottom: 12px">
      <el-button link type="primary" @click="loadData">重试</el-button>
    </el-alert>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="empNo" label="工号" width="120" />
      <el-table-column prop="name" label="姓名" width="120" />
      <el-table-column prop="dept" label="部门" />
      <el-table-column prop="phone" label="手机号" width="140" />
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === '在职' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="['ADMIN']" link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button v-permission="['ADMIN']" link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="table-footer">
      <el-pagination
        v-model:current-page="page.pageNum"
        v-model:page-size="page.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, prev, pager, next, sizes"
        @current-change="loadData"
        @size-change="handleSizeChange"
      />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑员工' : '新增员工'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="工号" prop="empNo">
          <el-input v-model="form.empNo" placeholder="如 E1001" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="部门" prop="dept">
          <el-input v-model="form.dept" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="在职" value="在职" />
            <el-option label="离职" value="离职" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { pageEmployees, createEmployee, updateEmployee, deleteEmployee } from '../api/access'
import { phoneRule } from '../utils/validators'

const query = reactive({ name: '', dept: '', status: '' })
const page = reactive({ pageNum: 1, pageSize: 10 })
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const loadError = ref('')

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await pageEmployees({ ...query, ...page })
    tableData.value = data.list || []
    total.value = data.total || 0
  } catch {
    // 具体错误文案由 axios 拦截器统一提示，此处只负责给出可重试的错误态
    loadError.value = '数据加载失败'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.pageNum = 1
  loadData()
}

function handleReset() {
  query.name = ''
  query.dept = ''
  query.status = ''
  handleSearch()
}

function handleSizeChange() {
  page.pageNum = 1
  loadData()
}

// ---------- 新增/编辑弹窗 ----------
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const emptyForm = { id: null, empNo: '', name: '', dept: '', phone: '', status: '在职' }
const form = reactive({ ...emptyForm })

const rules = {
  empNo: [{ required: true, message: '请输入工号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  dept: [{ required: true, message: '请输入部门', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入手机号', trigger: 'blur' }, phoneRule],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

function openDialog(row) {
  Object.assign(form, emptyForm, row ? { ...row } : {})
  dialogVisible.value = true
  if (formRef.value) formRef.value.clearValidate()
}

async function handleSubmit() {
  // 校验失败静默返回（validate 会 reject），避免 unhandled rejection
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (form.id) {
      await updateEmployee(form.id, form)
      ElMessage.success('修改成功')
    } else {
      await createEmployee(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row) {
  // 用户点「取消」时 confirm 会 reject，需 catch 后静默返回
  const ok = await ElMessageBox.confirm(`确认删除员工「${row.name}」吗？`, '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await deleteEmployee(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>
