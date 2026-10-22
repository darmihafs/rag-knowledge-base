# syntax=docker/dockerfile:1
# ============================================================================
# 后端镜像：两阶段构建，构建层用完整 JDK + Maven，运行层只留 JRE
#   docker build -t kb-backend ./backend
#   docker run --rm -p 8080:8080 --env-file .env kb-backend
# 依赖层单独缓存：pom.xml 不变时，改代码不会重新下载整包依赖。
# ============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# 测试在 CI 里跑（.github/workflows/ci.yml），镜像构建只打包，保持构建快
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseSerialGC"

WORKDIR /app
# 上传文档与向量库持久化文件都落在容器内相对路径 ./data，用 bind/volume 挂载持久化
RUN mkdir -p /app/data/uploads /app/data/embeddings \
    # 只为 HEALTHCHECK 探活，JRE 镜像本身不带 curl
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/kb-backend-*.jar /app/app.jar

# 非 root 运行：镜像被攻破时拿不到宿主 root
RUN useradd --system --create-home --uid 10001 app && chown -R app:app /app
USER app

EXPOSE 8080
# /api/health 在 AuthInterceptor 白名单里，无需 token 即可探活
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
