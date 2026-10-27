<template>
  <div class="page-card">
    <!-- 条件搜索 -->
    <el-form :inline="true" :model="query" class="search-bar">
      <el-form-item label="物资名称">
        <el-input v-model="query.name" placeholder="物资名称" clearable style="width: 140px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="编码">
        <el-input v-model="query.code" placeholder="一物一码" clearable style="width: 140px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="类别">
        <el-select v-model="query.category" placeholder="全部" clearable style="width: 110px">
          <el-option v-for="c in dict.materialCategories" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" placeholder="全部" clearable style="width: 110px">
          <el-option label="在库" value="在库" />
          <el-option label="在借" value="在借" />
          <el-option label="逾期" value="逾期" />
          <el-option label="维修中" value="维修中" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-toolbar">
      <span></span>
      <el-button type="primary" @click="openDialog()">新增物资</el-button>
    </div>

    <!-- 物资分页表格 -->
    <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" style="margin-bottom: 12px">
      <el-button link type="primary" @click="loadData">重试</el-button>
    </el-alert>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="code" label="编码" width="130" />
      <el-table-column prop="name" label="物资名称" width="140" />
      <el-table-column prop="category" label="类别" width="90" />
      <el-table-column prop="spec" label="规格型号" width="130" />
      <el-table-column prop="dept" label="责任部门" width="110" />
      <el-table-column prop="location" label="存放位置" />
      <el-table-column prop="valuable" label="贵重" width="80">
        <template #default="{ row }">
          <el-tag :type="row.valuable ? 'danger' : 'info'">{{ row.valuable ? '贵重' : '普通' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑物资' : '新增物资'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="编码" prop="code">
          <el-input v-model="form.code" placeholder="一物一码（RFID/二维码）" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="物资名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="类别" prop="category">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="c in dict.materialCategories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="规格型号" prop="spec">
          <el-input v-model="form.spec" />
        </el-form-item>
        <el-form-item label="责任部门" prop="dept">
          <el-input v-model="form.dept" />
        </el-form-item>
        <el-form-item label="存放位置" prop="location">
          <el-input v-model="form.location" />
        </el-form-item>
        <el-form-item label="是否贵重">
          <el-switch v-model="form.valuable" active-text="贵重（出库需审批）" inactive-text="普通" inline-prompt />
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
import { pageMaterials, createMaterial, updateMaterial, deleteMaterial } from '../api/stock'
import { useDictStore } from '../stores/dict'

const dict = useDictStore()

const query = reactive({ name: '', code: '', category: '', status: '' })
const page = reactive({ pageNum: 1, pageSize: 10 })
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const loadError = ref('')

// 状态 tag 着色：在库绿 / 在借蓝 / 逾期红 / 维修中橙
function statusTagType(status) {
  if (status === '在库') return 'success'
  if (status === '在借') return 'primary'
  if (status === '逾期') return 'danger'
  return 'warning'
}

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await pageMaterials({ ...query, ...page })
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
  query.name = ''
  query.code = ''
  query.category = ''
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
const emptyForm = { id: null, code: '', name: '', category: '', spec: '', dept: '', location: '', valuable: false }
const form = reactive({ ...emptyForm })

const rules = {
  code: [{ required: true, message: '请输入编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入物资名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择类别', trigger: 'change' }],
  spec: [{ required: true, message: '请输入规格型号', trigger: 'blur' }],
  dept: [{ required: true, message: '请输入责任部门', trigger: 'blur' }],
  location: [{ required: true, message: '请输入存放位置', trigger: 'blur' }]
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
      // 后端 PUT /materials/{id} 不再接受 code（不可变唯一键）与 status（由出入库流程维护）
      const payload = { ...form }
      delete payload.code
      delete payload.status
      await updateMaterial(form.id, payload)
      ElMessage.success('修改成功')
    } else {
      await createMaterial(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row) {
  const ok = await ElMessageBox.confirm(`确认删除物资「${row.name}」吗？`, '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await deleteMaterial(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>
