# =============================================================================
# 个人打卡定位项目 · 后端镜像（多阶段，构建发生在 GitHub Actions 上）
# -----------------------------------------------------------------------------
# 服务器只负责 docker pull，永远不编译。这条链路解决了两件事：
#   1) 服务器 4 GB 内存已用 2 GB，跑 Maven（下载依赖 + 编译 + 打包）会临时吃掉
#      1~1.5 GB，与 MySQL / Redis / Nginx 抢内存，OOM 时被杀的不一定是 Maven
#   2) 本地 Windows 构建、服务器 Linux 运行，过去要把镜像 gzip 后手工传输
#
# 什么时候构建：push 到 main 且改动 backend/** 时，
#   .github/workflows/docker-publish.yml 自动构建并推送到 ghcr.io/seanpoke/spot。
# 本文件保留手工构建能力（偶发调试用），构建上下文是【仓库根目录】：
#   docker build -t spot-api:latest -f deploy/Dockerfile .
#
# 注意：COPY 的路径带 backend/ 前缀，因为上下文是根目录——改为 backend/ 下
#      作上下文时这两行要一起去掉前缀，别只改一处。
# =============================================================================

# ---- 阶段 1：编译打包 ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先只拷 pom.xml 再拉依赖，源码变了但 pom 没变时这一层能命中缓存，
# 省掉每次重新下载上百 MB 的 Maven 依赖。
# dependency:go-offline 偶尔漏拉插件依赖，后面 package 仍可能联网，CI 环境有网不影响。
COPY backend/pom.xml .
RUN mvn -B dependency:go-offline

COPY backend/src ./src
RUN mvn -B clean package -DskipTests

# ---- 阶段 2：运行 ----
FROM eclipse-temurin:21-jre-alpine

# 时区（日志时间要和本地对得上，否则排查问题时全是对不上的 UTC）
RUN apk add --no-cache tzdata \
 && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
 && echo "Asia/Shanghai" > /etc/timezone

WORKDIR /app

# 非 root 运行，并提前建好上传目录（容器里由 volume 覆盖）
RUN addgroup -S app \
 && adduser -S -G app app \
 && mkdir -p /app/uploads \
 && chown -R app:app /app

# 只拷 jar。Spring Boot 会同时生成 xxx.jar 与 xxx.jar.original，
# 后者后缀不同，不会命中 *.jar。改名不为省事，是为了让 ENTRYPOINT 与 pom 里的
# finalName 解耦——以后改 artifactId 不用动这里。
COPY --from=builder /build/target/*.jar /app/app.jar

USER app
EXPOSE 8811

# 内存参数写死在镜像里，避免每次部署时手工传、也避免漏传导致堆爆掉宿主机。
# 这些数值是按 4 GB 服务器实测定的，见 AGENTS.md 第 14 条，调大前先问用户。
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar /app/app.jar"]
