# syntax=docker/dockerfile:1.7
# ============================================================================
# smart-park 前端镜像：多阶段构建（Node 构建 → Nginx 运行）
# 说明：nginx.conf 必须放在本目录（构建上下文内），运行时由镜像承载，不做卷挂载
# ============================================================================

######################### 阶段一：构建 #########################
FROM node:18-alpine AS builder
WORKDIR /build

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
