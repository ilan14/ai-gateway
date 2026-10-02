# AI Gateway

## 使用 Docker 运行

需要 Docker Engine / Docker Desktop 和 Docker Compose v2。构建在容器内完成，无需本机安装 Java 或 Maven。

```sh
docker compose up -d --build
```

访问 http://localhost:8080。Compose 会启动 MySQL、Redis，首次创建数据库时执行 `src/main/resources/schema.sql`，等待依赖就绪后启动应用。默认使用 stub 模型。

可在项目根目录创建 `.env`，或通过 shell 环境变量设置以下参数：

```dotenv
DB_PASSWORD=replace-with-your-password
DEEP_SEEK_API_KEY=
QWEN_API_KEY=
```

默认数据库密码仅供本地开发。`.env` 不会复制进镜像，请勿提交包含密钥的文件。已有 MySQL 数据卷不会重新执行初始化脚本；修改密码时也需要同步修改数据库中的密码。

```sh
docker compose logs -f app
docker compose down
```

数据库和 Redis 数据保存在命名卷中，`down` 会保留数据。`docker compose down -v` 会删除这些数据。

## 连接已有 MySQL 和 Redis

先手动执行 `src/main/resources/schema.sql` 初始化数据库，再构建并启动应用：

```sh
docker build -t ai-gateway .
docker run -d --name ai-gateway -p 8080:8080 \
  -e SPRING_DATASOURCE_URL='jdbc:mysql://host.docker.internal:3306/ai_gateway?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false' \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=your-password \
  -e REDIS_HOST=host.docker.internal \
  -e DEEP_SEEK_API_KEY= \
  -e QWEN_API_KEY= \
  ai-gateway
```

`host.docker.internal` 用于 Docker Desktop 访问宿主机；Linux Docker Engine 需要加上 `--add-host=host.docker.internal:host-gateway`。连接其他服务器时替换为实际地址。可以通过 `JAVA_TOOL_OPTIONS` 传入 JVM 参数，例如 `-e JAVA_TOOL_OPTIONS='-Xmx512m'`。
