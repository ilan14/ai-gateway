#!/usr/bin/env bash
set -euo pipefail

# 默认已正确安装 JDK 25。
LOG_FILE=logs/start.log
PID_FILE=logs/app.pid

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# 加载项目根目录的 .env，自动导出变量供 Maven 和 Java 进程使用。
if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  source "$PROJECT_DIR/.env"
  set +a
fi

echo "跳过单元测试并打包……"
./mvnw -B -ntp -DskipTests clean package

shopt -s nullglob
JARS=(target/*.jar)
if [[ ${#JARS[@]} -ne 1 ]]; then
  echo "期望 target 下存在一个可执行 jar，实际找到 ${#JARS[@]} 个，请检查构建产物。" >&2
  exit 1
fi

JVM_ARGS="-Dfile.encoding=UTF-8
          -Xms256m
          -Xmx512m
          "

mkdir -p -- "$(dirname -- "$LOG_FILE")" "$(dirname -- "$PID_FILE")"
echo "后台启动 ${JARS[0]}。"
nohup java $JVM_ARGS -jar "${JARS[0]}" >> "$LOG_FILE" 2>&1 < /dev/null &
APP_PID=$!
printf '%s\n' "$APP_PID" > "$PID_FILE"
echo "启动进程已创建，PID：${APP_PID}；应用是否就绪请查看日志。"
echo "查看日志：tail -f ${PROJECT_DIR}/${LOG_FILE}"
echo "停止应用：kill ${APP_PID}"
