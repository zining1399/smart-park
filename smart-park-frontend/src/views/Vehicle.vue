<template>
  <div class="page-card">
    <!-- 条件搜索 -->
    <el-form :inline="true" :model="query" class="search-bar">
      <el-form-item label="车牌号">
        <el-input v-model="query.plateNo" placeholder="车牌号" clearable style="width: 140px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="车主类型">
        <el-select v-model="query.ownerType" placeholder="全部" clearable style="width: 120px">
          <el-option v-for="t in dict.vehicleOwnerTypes" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
          <el-option label="正常" value="正常" />
          <el-option label="黑名单" value="黑名单" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-toolbar">
      <span></span>
      <el-button v-permission="['ADMIN']" type="primary" @click="openDialog()">新增车辆</el-button>
    </div>

    <!-- 分页表格 -->
    <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" style="margin-bottom: 12px">
      <el-button link type="primary" @click="loadData">重试</el-button>
    </el-alert>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="plateNo" label="车牌号" width="120" />
      <el-table-column prop="ownerType" label="车主类型" width="100" />
      <el-table-column prop="ownerName" label="车主姓名" width="120" />
      <el-table-column prop="dept" label="归属部门" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === '正常' ? 'success' : 'danger'">{{ row.status }}</el-tag>
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
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑车辆' : '新增车辆'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="车牌号" prop="plateNo">
          <el-input v-model="form.plateNo" placeholder="如 新A12345" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="车主类型" prop="ownerType">
          <el-select v-model="form.ownerType" style="width: 100%">
            <el-option v-for="t in dict.vehicleOwnerTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="车主姓名" prop="ownerName">
          <el-input v-model="form.ownerName" />
        </el-form-item>
        <el-form-item label="归属部门">
          <el-input v-model="form.dept" placeholder="可空" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="正常" value="正常" />
            <el-option label="黑名单" value="黑名单" />
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
import { pageVehicles, createVehicle, updateVehicle, deleteVehicle } from '../api/access'
import { useDictStore } from '../stores/dict'
import { plateRule } from '../utils/validators'

const dict = useDictStore()

const query = reactive({ plateNo: '', ownerType: '', status: '' })
const page = reactive({ pageNum: 1, pageSize: 10 })
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const loadError = ref('')

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await pageVehicles({ ...query, ...page })
    tableData.value = data.list || []
    total.value = data.total || 0
  } catch {
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
  query.plateNo = ''
  query.ownerType = ''
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
const emptyForm = { id: null, plateNo: '', ownerType: '员工', ownerName: '', dept: '', status: '正常' }
const form = reactive({ ...emptyForm })

const rules = {
  plateNo: [{ required: true, message: '请输入车牌号', trigger: 'blur' }, plateRule],
  ownerType: [{ required: true, message: '请选择车主类型', trigger: 'change' }],
  ownerName: [{ required: true, message: '请输入车主姓名', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

function openDialog(row) {
  Object.assign(form, emptyForm, row ? { ...row } : {})
  dialogVisible.value = true
  if (formRef.value) formRef.value.clearValidate()
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (form.id) {
      // 后端 PUT /vehicles/{id} 不再接受 plateNo（不可变业务唯一键），编辑提交剔除
      const payload = { ...form }
      delete payload.plateNo
      await updateVehicle(form.id, payload)
      ElMessage.success('修改成功')
    } else {
      await createVehicle(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row) {
  const ok = await ElMessageBox.confirm(`确认删除车辆「${row.plateNo}」吗？`, '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await deleteVehicle(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>
