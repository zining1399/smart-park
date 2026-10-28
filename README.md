# 智慧园区管理系统（Smart Park）

前后端分离项目，依据《智慧园区管理系统建设方案.md》与《智慧矿山园区生态.docx》搭建，覆盖 **智慧门禁 / 访客接待 / 智慧仓管 / 运营看板 / 告警中心** 五大模块。

## 目录结构

```
smart-park/
├── README.md                    ← 本文件（设计说明 + 假设清单 + 启动方式）
├── docs/
│   └── api-contract.md          ← 前后端接口契约（唯一事实来源）
├── smart-park-backend/          ← Spring Boot 3 后端（端口 8080，context-path /api）
└── smart-park-frontend/         ← Vue 3 + Vite 前端（端口 5173，代理 /api → 8080）
```

## 技术栈

| 端  | 技术                                                                           |
| -- | ---------------------------------------------------------------------------- |
| 后端 | Java 17 · Spring Boot 3.2 · MyBatis-Plus 3.5 · MySQL 8（Flyway 迁移）· Maven · Lombok |
| 前端 | Vue 3 · Vite 5 · Element Plus · Vue Router · Pinia · Axios · ECharts（看板）     |

## 资料中未明确、本次实现采用的假设

1. **数据库**：资料未指定。采用 **MySQL 8**，表结构由 Flyway `V1__init.sql` 版本化迁移（dev 本地与生产同库同方言，不再用 H2 兜底）；连接信息走环境变量，见下方「本地启动」。
2. **登录鉴权**：资料中的统一身份中心属既有平台能力。本项目不实现登录，接口暂不做鉴权，前端直接进入工作台（axios 中预留 token 拦截器位置）。
3. **设备接入**：车牌/人脸/RFID 等终端感知层不在软件交付范围内。通行流水通过「模拟通行」接口产生（等价于终端上报），门禁白名单/权限校验逻辑在服务端实现。
4. **三维一张图**：复用已有三维模型与地图，本项目仅提供运营看板（ECharts 图表 + 统计卡片），不做三维渲染。
5. **访客证件核验**：证件真伪核验属硬件/公安接口能力，本项目只做字段录入与脱敏展示（身份证保留前 6 后 4 位）。
6. **审批流**：资料仅说明「贵重/限额物资需审批」。假设：物资 `valuable=true` 时出库申领需审批（待审批→已批准/已驳回，批准后才执行出库），普通物资免审直接出库。
7. **逾期规则**：出库时填写预计归还时间，超过未还即「逾期」，由定时任务扫描产生告警，并按方案限制逾期人员再次领用。
8. **门岗/区域枚举**：门岗（东门/西门/南门/货运门），区域（办公区/生产区/仓储区/生活区）。
9. **权限模型**：采用方案中「区域 + 时段 + 有效期」三维模型的简化版：目标（人/车）+ 区域 + 生效/失效时间。

## 业务状态机（依据方案 4.4）

- **物资**：在库 → 在借 →（归还）在库；超期 → 逾期；故障 → 维修中 → 在库
- **访客**：已预约 → 在场 → 已签销；超时未离 → 滞留预警
- **权限时效**：生效 → 临期提醒 → 失效（定时任务每日扫描）

## 本地启动

### 后端（需要 JDK 17+ 与本地 MySQL 8）

先在 MySQL 8 里建库与开发账号（只需一次）：

```sql
CREATE DATABASE smart_park CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'smart_park'@'localhost' IDENTIFIED BY 'smart_park_dev';
GRANT ALL PRIVILEGES ON smart_park.* TO 'smart_park'@'localhost';
FLUSH PRIVILEGES;
```

启动（Flyway 自动建表，无需手工跑 SQL；初始 admin/gate/store 账号密码打印在启动日志里）：

```bash
cd smart-park-backend
# Windows PowerShell：
$env:SPRING_DATASOURCE_USERNAME='smart_park'
$env:SPRING_DATASOURCE_PASSWORD='smart_park_dev'
mvn spring-boot:run
# 启动后接口前缀：http://localhost:8080/api
# 健康检查：http://localhost:8080/api/actuator/health
```

> dev 默认 profile 即连 `localhost:3306/smart_park`；H2 已降为 test scope，仅供 Testcontainers 之外的纯单测使用，不再提供本地 Web 控制台。

### 前端（需要 Node 18+）

```bash
cd smart-park-frontend
npm install
npm run dev
# 访问 http://localhost:5173 ，dev server 已将 /api 代理到 http://localhost:8080
```

## 跨域（CORS）

双保险：① 后端 `CorsConfig` 全局放行开发环境来源；② 前端 Vite dev server 配置 `/api` 代理。生产部署建议走同源 Nginx 反代。

## 生产部署（客户现场 Docker 一键部署）

```bash
cp .env.example .env && chmod 600 .env && vi .env   # 填写所有空值（密码用 openssl rand 生成）
./scripts/init-secrets.sh                           # 生成 mysql/*.cnf（600，严禁入库）
docker compose --env-file .env up -d
./scripts/smoke.sh                                  # 真实容器冒烟：断言全通过才允许对外服务
```

- 对外只有 Nginx 一个入口，3306 / 8080 均不发布到宿主机；数据落在 `smart-park-mysql-data` 卷
- 回滚 = 改 `.env` 的 `APP_VERSION` 后重建 backend / frontend 容器（<30 秒）
- 完整运维手册（巡检 / 备份恢复 / 发布回滚 / 数据导入 / 归档 / 割接检查单）：`docs/ops/runbook.md`
