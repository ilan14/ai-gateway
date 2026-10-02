# AI Gateway

## 本地一键测试、打包和启动

安装 JDK 25 后，在项目根目录执行：

```sh
./start.sh
```

脚本使用 Maven Wrapper 和本地 Maven 缓存，执行 `clean package`，包含单元测试；测试或打包失败时不会启动应用。成功后以前台方式运行 jar，Ctrl+C 停止。

JVM 参数 `JVM_ARGS`、端口 `SERVER_PORT`、应用参数 `APP_ARGS` 和 Maven 参数 `MAVEN_ARGS` 集中声明在 `start.sh` 顶部，可直接修改。默认 JVM 堆内存为 256–512 MB，端口为 8080。依赖完整时可设置 `MAVEN_ARGS=("-o")` 离线构建；修改 Java 版本路径可设置 `JAVA_HOME`。

MySQL、Redis 和模型密钥使用 `application.yaml` 中的环境变量配置。脚本会在打包前自动加载项目根目录的 `.env`，并导出其中的变量供 Maven 和 Java 使用；文件不存在时继续运行。`.env` 使用 Bash 赋值语法（例如 `DB_PASSWORD='含空格的密码'`），其中的赋值会覆盖同名环境变量。也可以直接通过环境变量传入未在 `.env` 中设置的配置：

```sh
DB_URL=localhost DB_USERNAME=root DB_PASSWORD=your-password \
  REDIS_HOST=localhost ./start.sh
```

## 使用 Docker 运行

需要 Docker Engine / Docker Desktop 和 Docker Compose v2。构建在容器内完成，无需本机安装 Java 或 Maven。

Compose 只启动 Java 应用，连接单独部署的 MySQL 和 Redis，不创建或管理数据库服务及数据卷。

首次部署前，在已有 MySQL 中手动执行 `src/main/resources/schema.sql` 初始化数据库。已有数据库应按实际情况执行迁移，不要重复初始化。确保 MySQL 和 Redis 已运行，并允许应用容器连接。

在项目根目录创建配置：

```sh
cp .env.example .env
chmod 600 .env
```

修改 `.env` 中的地址、端口、账号和密码：

```dotenv
DB_URL=host.docker.internal
DB_PORT=3306
DB_USERNAME=root
DB_PASSWORD=replace-with-your-password
REDIS_HOST=host.docker.internal
REDIS_PORT=6379
REDIS_PASSWORD=
DEEP_SEEK_API_KEY=
QWEN_API_KEY=
```

`DB_URL` 表示 MySQL 主机地址，不包含协议和端口。数据库默认是 `ai_gateway`；如需自定义数据库名称或 JDBC 参数，可设置完整的 `SPRING_DATASOURCE_URL` 覆盖默认连接 URL。

MySQL 和 Redis 位于 Ubuntu 宿主机时，可以使用 `host.docker.internal`，Compose 已配置 Linux 所需的 `host-gateway` 映射。容器内的 `localhost` 指向应用容器自身；宿主机服务需要监听容器可访问的接口，单独部署的数据库容器需要发布相应端口。位于其他服务器时，替换为应用容器可访问的服务器 IP 或域名，并配置相应访问权限。

`.env` 不会复制进镜像，请勿提交包含密钥的文件。`DB_PASSWORD` 必须设置；Redis 没有密码时 `REDIS_PASSWORD` 留空。API Key 可以留空，项目默认使用 stub 模型。

启动或更新应用：

```sh
docker compose up -d --build
docker compose ps
docker compose logs -f app
```

访问 http://localhost:8080，远程访问时使用 Ubuntu 服务器 IP。Ubuntu 当前用户没有 Docker 权限时，在 Docker 命令前加 `sudo`。

停止应用：

```sh
docker compose down
```

停止应用不会影响单独部署的 MySQL、Redis 或其数据。Compose 不再等待数据库健康检查，启动前应确保外部依赖可用。

## 直接运行镜像

也可以沿用上述 `.env`，直接构建和运行镜像。此方式使用默认数据库名 `ai_gateway` 和 MySQL 端口 `3306`；自定义端口或数据库名时，请在 `.env` 中设置完整的 `SPRING_DATASOURCE_URL`。

```sh
docker build -t ai-gateway .
docker run -d --name ai-gateway --restart unless-stopped -p 8080:8080 \
  --add-host=host.docker.internal:host-gateway \
  --env-file .env \
  ai-gateway
```

可以通过 `JAVA_TOOL_OPTIONS` 传入 JVM 参数，例如为 `docker run` 添加 `-e JAVA_TOOL_OPTIONS='-Xmx512m'`。
