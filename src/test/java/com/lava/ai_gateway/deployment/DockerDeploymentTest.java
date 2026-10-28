package com.lava.ai_gateway.deployment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 部署配置回归检查，不连接真实数据库或 Docker。 */
class DockerDeploymentTest {
    @Test
    void composeShouldImportEnvAndUseHostPorts() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("compose", new FileSystemResource("compose.yaml")).getFirst();
        String prefix = "services.ai-gateway.";
        assertThat(source.getProperty(prefix + "env_file[0]")).isEqualTo(".env");
        assertThat(source.getProperty(prefix + "environment")).isNull();
        assertThat(source.getProperty(prefix + "extra_hosts[0]"))
                .isEqualTo("host.docker.internal:host-gateway");
        assertThat(source.getProperty(prefix + "volumes[0]")).isEqualTo("./logs:/app/logs");
        assertThat(source.getProperty("networks.dependencies.external")).isNull();
        assertThat(source.getProperty(prefix + "healthcheck.test[7]"))
                .isEqualTo("http://localhost:8080/actuator/health");
        assertThat(source.getProperty(prefix + "mem_limit")).isEqualTo("${APP_MEMORY_LIMIT:-1g}");
        assertThat(source.getProperty(prefix + "logging.options.max-size")).isEqualTo("10m");
    }

    @Test
    void mavenShouldWarmDependenciesBeforeCopyingSourcesAndShareCache() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        assertThat(dockerfile.indexOf("COPY mvnw pom.xml"))
                .isLessThan(dockerfile.indexOf("dependency:go-offline"));
        assertThat(dockerfile.indexOf("dependency:go-offline"))
                .isLessThan(dockerfile.indexOf("COPY src/ src/"));
        assertThat(dockerfile.split("id=ai-gateway-maven,target=/root/.m2,sharing=locked", -1))
                .hasSize(3);
        assertThat(dockerfile).contains("--no-install-recommends curl", "--uid 10001", "USER app");
        assertThat(Files.readString(Path.of(".dockerignore"))).contains(".env", ".env.*");
        assertThat(Files.readString(Path.of(".env.example")))
                .contains("DB_URL=host.docker.internal", "REDIS_HOST=host.docker.internal", "JAVA_TOOL_OPTIONS=")
                .doesNotContain("DOCKER_NETWORK=");
        assertThat(Files.readString(Path.of("docs/docker-deployment.md")))
                .contains("docker compose config --quiet", "docker compose up -d --build", "10001:10001");
    }
}
