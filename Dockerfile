# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# 依赖层仅随 Maven 配置变化失效，源码修改复用已下载的依赖。
RUN --mount=type=cache,id=ai-gateway-maven,target=/root/.m2,sharing=locked \
    ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,id=ai-gateway-maven,target=/root/.m2,sharing=locked ./mvnw -B -ntp -DskipTests package \
    && cp target/ai-gateway-*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app app \
    && mkdir -p /app/logs && chown app:app /app/logs
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
