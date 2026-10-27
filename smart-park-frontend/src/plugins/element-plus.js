// Element Plus 按需引入：显式注册本项目实际使用的组件 + 对应样式
//
// 为什么不用 unplugin-vue-components：其 ElementPlusResolver（v32）生成的是
// element-plus/es/el-<name> 路径，而 Element Plus 2.14 已移除该目录结构，
// 会回退成整库引入（实测 817KB → 本方案后见构建产物对比）。
// 显式注册不依赖插件行为，产物体积可确定性验证。
// 注：EP 2.14 中部分子组件没有独立入口（如 aside/menu-item/form-item/option/table-column），
// 需从其父组件入口具名导入（container/menu/form/select/table/dropdown/radio）
import { ElContainer, ElAside, ElHeader, ElMain } from 'element-plus/es/components/container/index.mjs'
import { ElMenu, ElMenuItem, ElSubMenu } from 'element-plus/es/components/menu/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import { ElSelect, ElOption } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElDropdown, ElDropdownMenu, ElDropdownItem } from 'element-plus/es/components/dropdown/index.mjs'
import { ElRadioGroup, ElRadio } from 'element-plus/es/components/radio/index.mjs'
import ElIcon from 'element-plus/es/components/icon/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElPagination from 'element-plus/es/components/pagination/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElDatePicker from 'element-plus/es/components/date-picker/index.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElResult from 'element-plus/es/components/result/index.mjs'
import ElConfigProvider from 'element-plus/es/components/config-provider/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElLoadingDirective } from 'element-plus/es/components/loading/index.mjs'

// 基础样式 + 按需组件样式（整包 dist/index.css 为 362KB，这里只取用到的部分）
// 必须静态导入：动态 import() 会把样式拆成异步 chunk，导致首屏样式延迟
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-container.css'
import 'element-plus/theme-chalk/el-aside.css'
import 'element-plus/theme-chalk/el-header.css'
import 'element-plus/theme-chalk/el-main.css'
import 'element-plus/theme-chalk/el-menu.css'
import 'element-plus/theme-chalk/el-menu-item.css'
import 'element-plus/theme-chalk/el-sub-menu.css'
import 'element-plus/theme-chalk/el-icon.css'
import 'element-plus/theme-chalk/el-dropdown.css'
import 'element-plus/theme-chalk/el-dropdown-menu.css'
import 'element-plus/theme-chalk/el-dropdown-item.css'
import 'element-plus/theme-chalk/el-tag.css'
import 'element-plus/theme-chalk/el-card.css'
import 'element-plus/theme-chalk/el-row.css'
import 'element-plus/theme-chalk/el-col.css'
import 'element-plus/theme-chalk/el-form.css'
import 'element-plus/theme-chalk/el-form-item.css'
import 'element-plus/theme-chalk/el-input.css'
import 'element-plus/theme-chalk/el-select.css'
import 'element-plus/theme-chalk/el-option.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-table.css'
import 'element-plus/theme-chalk/el-table-column.css'
import 'element-plus/theme-chalk/el-pagination.css'
import 'element-plus/theme-chalk/el-dialog.css'
import 'element-plus/theme-chalk/el-date-picker.css'
import 'element-plus/theme-chalk/el-alert.css'
import 'element-plus/theme-chalk/el-result.css'
import 'element-plus/theme-chalk/el-config-provider.css'
import 'element-plus/theme-chalk/el-radio-group.css'
import 'element-plus/theme-chalk/el-radio.css'
import 'element-plus/theme-chalk/el-switch.css'
import 'element-plus/theme-chalk/el-loading.css'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'

const COMPONENTS = {
  ElContainer,
  ElAside,
  ElHeader,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElSubMenu,
  ElIcon,
  ElDropdown,
  ElDropdownMenu,
  ElDropdownItem,
  ElTag,
  ElCard,
  ElRow,
  ElCol,
  ElForm,
  ElFormItem,
  ElInput,
  ElSelect,
  ElOption,
  ElButton,
  ElTable,
  ElTableColumn,
  ElPagination,
  ElDialog,
  ElDatePicker,
  ElAlert,
  ElResult,
  ElConfigProvider,
  ElRadioGroup,
  ElRadio,
  ElSwitch
}

export default function registerElementPlus(app) {
  Object.entries(COMPONENTS).forEach(([name, component]) => app.component(name, component))
  app.directive('loading', ElLoadingDirective)
}
