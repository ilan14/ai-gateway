# Docker 部署

应用通过 `host.docker.internal` 访问宿主机发布的 MySQL、Redis 端口，不需要创建共享外部网络。确认端口可从应用容器访问；Linux 上仅绑定宿主机 127.0.0.1 的端口可能无法通过宿主机网关访问。数据库及表需提前初始化。

## 配置和启动

1. 复制 `.env.example` 为 `.env`，填写实际数据库、Redis 端口和密码。MySQL 非默认端口使用完整 `SPRING_DATASOURCE_URL`。
2. 所有应用变量通过 `env_file: .env` 注入容器，新增变量不必修改 Compose。Compose 本身也读取 `.env` 替换端口和内存参数；同名 shell 变量优先影响 Compose 插值。不要提交真实密码，`.env` 已被排除出镜像构建上下文。
3. 日志保留在宿主机 `./logs`，镜像使用 UID/GID 10001。部署时先准备目录权限（现有日志也需要可写）：

```sh
mkdir -p logs
sudo chown -R 10001:10001 logs
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
docker compose ps
docker compose logs --tail=100 ai-gateway
```

启动前停止占用相同端口的旧 Java 服务，也可在 `.env` 中设置其他 `APP_PORT` 并行验证。仅由本机反向代理访问时设置 `APP_BIND_ADDRESS=127.0.0.1`。

## 构建缓存

- `docker compose up -d --build` 在启动前构建，适用于首次部署和源码更新；源码变化不会让普通 `up` 自动重新构建已有镜像。
- Dockerfile 先复制 Maven Wrapper 和 pom.xml，执行 `dependency:go-offline`，再复制源码打包。仅修改源码时，依赖预下载层可直接复用。
- 两个 Maven 步骤共享 BuildKit 的 `/root/.m2` 缓存，复用依赖、插件和 Wrapper 下载。首次构建、依赖变更或构建缓存被清理后仍可能下载；缓存属于当前构建器，不随仓库同步。
- 不强制 Maven 离线打包，允许补齐插件运行时依赖；默认也不使用 `-U` 强制刷新快照。
- 普通启动使用 `docker compose up -d`；修改 `.env` 后也使用该命令更新容器，`restart` 不会重新加载变量。

## 运维

- 默认容器内存上限 1 GiB，示例 JVM 最大堆为 512 MiB，预留堆外内存；未设置 CPU 限额。
- 应用日志由 Logback 轮转；Docker 控制台日志每文件最多 10 MiB，保留 3 个。GC 日志和 OOM 转储也在 logs 目录，堆转储需自行清理。
- `/actuator/health` 用于健康检查，不健康不会自动重启；restart 策略只处理进程退出。
- 不调用 `start.sh`。停止使用 `docker compose down`，不会删除宿主机日志。
- 单元测试使用 H2 或 Mock，不连接真实数据库：`./mvnw -B test`。构建镜像跳过测试，发布前需单独执行。
