package pl.aiwcorpo.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;

/** What the production configuration must keep, checked without starting a server. */
class ProductionConfigurationTest {

    @Test
    void outsideDevTheAdapterDemandsAClientCertificateFromTheGateway() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));

        PropertySource<?> production = documents.stream()
                .filter(document -> "!dev".equals(document.getProperty("spring.config.activate.on-profile"))).findFirst().orElseThrow();
        assertThat(String.valueOf(production.getProperty("server.ssl.client-auth"))).isEqualTo("need");
        assertThat(String.valueOf(production.getProperty("server.ssl.bundle"))).isEqualTo("mcp-server");
        assertThat(documents).allSatisfy(document -> {
            Object clientAuth = document.getProperty("server.ssl.client-auth");
            assertThat(clientAuth == null || "need".equals(String.valueOf(clientAuth))).isTrue();
        });
    }

    @Test
    void theRegistryIdentityIsAKeystoreOfItsOwnNotTheServerCertificate() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        PropertySource<?> production = documents.stream()
                .filter(document -> "!dev".equals(document.getProperty("spring.config.activate.on-profile"))).findFirst().orElseThrow();

        assertThat(String.valueOf(production.getProperty("spring.ssl.bundle.jks.adapter-registry.keystore.location")))
                .contains("MCP_REGISTRY_TLS_KEYSTORE").doesNotContain("MCP_TLS_KEYSTORE}");
    }

    @Test
    void theVersionOfTheManifestIsTheVersionOfTheBuild() throws IOException {
        PropertySource<?> base = new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml")).getFirst();
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher version = Pattern.compile("</parent>.*?<version>([^<]+)</version>", Pattern.DOTALL).matcher(pom);
        Matcher own = Pattern.compile("<artifactId>mcp-server-adapter</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);

        assertThat(own.find()).isTrue();
        assertThat(version.find()).isTrue();
        assertThat(String.valueOf(base.getProperty("aiwcorpo.mcp.platform.manifest.version"))).isEqualTo(own.group(1));
        assertThat(String.valueOf(base.getProperty("aiwcorpo.mcp.platform.manifest.accepted-credential-types")))
                .isEqualTo("${MCP_ACCEPTED_CREDENTIAL_TYPES:user-secret}");
    }

    @Test
    void devIsReachableOnLoopbackOnly() throws IOException {
        Map<String, Object> dev = (Map<String, Object>) new YamlPropertySourceLoader()
                .load("application-dev.yml", new ClassPathResource("application-dev.yml")).getFirst().getSource();
        assertThat(String.valueOf(dev.get("server.address"))).isEqualTo("127.0.0.1");
    }

    @Test
    void theCredentialOfTheUserTravelsOnlyOverHttpsUnlessTheOperatorOrDevSaysOtherwise() {
        assertThatThrownBy(() -> check("http://mcp-atlassian:8000", false, new MockEnvironment())).hasMessageContaining("https");
        assertThatCode(() -> check("https://mcp-atlassian.internal:8443", false, new MockEnvironment())).doesNotThrowAnyException();
        assertThatCode(() -> check("http://mcp-atlassian:8000", true, new MockEnvironment())).doesNotThrowAnyException();
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> check("http://127.0.0.1:18931", false, dev)).doesNotThrowAnyException();
    }

    @Test
    void anAddressWithCredentialsOrAQueryIsRefusedInEveryProfile() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatThrownBy(() -> check("https://user:secret@mcp-atlassian:8443", true, dev)).hasMessageContaining("user info");
        assertThatThrownBy(() -> check("https://mcp-atlassian:8443?token=x", true, dev)).hasMessageContaining("query");
        assertThatThrownBy(() -> check("ftp://mcp-atlassian", true, dev)).hasMessageContaining("https");
        assertThatThrownBy(() -> check(null, true, dev)).hasMessageContaining("not set");
    }

    private static void check(String url, boolean allowPlainHttp, MockEnvironment environment) {
        AdapterConfiguration.requireSafeUrl(new AdapterProperties.Foreign(url == null ? null : URI.create(url), "/mcp", "Token {credential}",
                "", Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(1), allowPlainHttp, null),
                environment);
    }
}
