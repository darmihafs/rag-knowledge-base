# syntax=docker/dockerfile:1
# ============================================================================
# 前端镜像：Node 构建 Vite 产物，交给 nginx 提供静态资源并反代 /api
#   docker build -t kb-frontend ./frontend
# 构建产物是纯静态文件，运行期不需要 Node。
# ============================================================================
FROM node:22-alpine AS build
WORKDIR /build

# 依赖层单独缓存：package*.json 不变时，改代码不会重新 npm install
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# build 脚本 = vue-tsc -b && vite build，类型错误会直接让镜像构建失败
RUN npm run build

FROM nginx:1.27-alpine AS runtime
ENV TZ=Asia/Shanghai
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
# 该镜像的 nginx 只在容器里监听，宿主机通过 compose 的端口映射访问
