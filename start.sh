#!/usr/bin/env bash
set -euo pipefail

# 启动配置：按需修改。需要 JDK 25；设置 JAVA_HOME 可指定 JDK。
JVM_ARGS=("-Xms256m" "-Xmx512m" "-Dfile.encoding=UTF-8")
SERVER_PORT=8080
APP_ARGS=("--server.port=${SERVER_PORT}")
# 依赖完整时可改为 MAVEN_ARGS=("-o")，仅使用本地 Maven 缓存。
MAVEN_ARGS=()

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# 加载项目根目录的 .env，自动导出变量供 Maven 和 Java 进程使用。
if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  source "$PROJECT_DIR/.env"
  set +a
fi

JAVA_BIN=java
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
fi
if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  echo "未找到 Java，请安装 JDK 25 或配置 JAVA_HOME。" >&2
  exit 1
fi

echo "执行单元测试并打包……"
./mvnw -B -ntp ${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"} clean package

shopt -s nullglob
JARS=(target/*.jar)
if [[ ${#JARS[@]} -ne 1 ]]; then
  echo "期望 target 下存在一个可执行 jar，实际找到 ${#JARS[@]} 个，请检查构建产物。" >&2
  exit 1
fi

echo "启动 ${JARS[0]}，端口 ${SERVER_PORT}（Ctrl+C 停止）。"
exec "$JAVA_BIN" ${JVM_ARGS[@]+"${JVM_ARGS[@]}"} -jar "${JARS[0]}" ${APP_ARGS[@]+"${APP_ARGS[@]}"}
