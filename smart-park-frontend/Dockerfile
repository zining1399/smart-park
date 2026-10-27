# syntax=docker/dockerfile:1.7
# ============================================================================
# smart-park 前端镜像：多阶段构建（Node 构建 → Nginx 运行）
# 说明：nginx.conf 必须放在本目录（构建上下文内），运行时由镜像承载，不做卷挂载
# ============================================================================

######################### 阶段一：构建 #########################
FROM node:18-alpine AS builder
WORKDIR /build

# ⚠ Vite 的 VITE_* 是【构建期】变量：会被内联进产物 JS，运行时改环境变量无效。
#    修改前端 API 地址或部署子路径必须重新 build 镜像（仅重启容器无效）。
#
# ★ 三个变量一律【默认留空】，由前端代码默认值兜底（API=/api、BASE=/）：
#   仓库 .gitignore 排除了 .env.*，Docker 构建上下文里不会有 .env.production，
#   若在此硬编码默认值，客户现场一构建就可能拿到与 nginx 反代不一致的地址而白屏。
#   前端读取顺序：VITE_API_BASE_URL || VITE_API_BASE（兼容别名）|| '/api'
ARG VITE_API_BASE_URL=
ARG VITE_API_BASE=
ARG VITE_BASE=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_API_BASE=$VITE_API_BASE \
    VITE_BASE=$VITE_BASE

# 内网/加速场景可注入镜像源（docker build --build-arg NPM_REGISTRY=...）
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry "$NPM_REGISTRY"

# package-lock.json 必须存在：可重复构建依赖它，缺失即构建失败（不做静默回退，
# 否则客户现场会装出与验收不一致的依赖版本）
COPY package.json package-lock.json ./
RUN test -f package-lock.json || { echo "[FATAL] 缺少 package-lock.json，禁止构建"; exit 1; }
RUN npm ci

COPY . .
RUN npm run build

######################### 阶段二：运行 #########################
FROM nginx:1.27-alpine
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html

ENV TZ=Asia/Shanghai
EXPOSE 80

# alpine 无 curl，使用 busybox wget
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost/ >/dev/null 2>&1 || exit 1
