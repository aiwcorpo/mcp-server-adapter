package pl.aiwcorpo.mcp.adapter;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignCatalog;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignMcpClient;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignTool;
import pl.aiwcorpo.mcp.adapter.tool.ForeignTools;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;
import pl.aiwcorpo.mcp.platform.observability.ToolCallInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdapterProperties.class)
class AdapterConfiguration {

    @Bean
    ForeignMcpClient foreignMcpClient(AdapterProperties properties, ObjectProvider<SslBundles> bundles, Environment environment) {
        AdapterProperties.Foreign foreign = properties.foreign();
        requireSafeUrl(foreign, environment);
        requireSafeFormat(foreign.authorizationFormat());
        SSLContext tls = foreign.sslBundle() == null || foreign.sslBundle().isBlank() ? null
                : bundles.getObject().getBundle(foreign.sslBundle()).createSslContext();
        return new ForeignMcpClient(ForeignMcpClient.httpClient(foreign, tls), foreign);
    }

    /**
     * The credential of the user travels to this address. https always, except in {@code dev} and where the operator says that the
     * sidecar and the server share a private network; an address with credentials in it is refused in every case.
     */
    static void requireSafeUrl(AdapterProperties.Foreign foreign, Environment environment) {
        URI url = foreign.url();
        if (url == null || url.getHost() == null) {
            throw new IllegalStateException("aiwcorpo.mcp.adapter.foreign.url is not set (the base URL of the foreign MCP server)");
        }
        if (url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null) {
            throw new IllegalStateException("aiwcorpo.mcp.adapter.foreign.url must not carry user info, a query or a fragment");
        }
        boolean https = "https".equalsIgnoreCase(url.getScheme());
        boolean http = "http".equalsIgnoreCase(url.getScheme());
        boolean dev = environment.acceptsProfiles(Profiles.of(PlatformConfigurationGuard.DEV_PROFILE));
        if (!https && !(http && (dev || foreign.allowPlainHttp()))) {
            throw new IllegalStateException("the foreign server must be reached over https (the credential of the user travels "
                    + "there); http only in the dev profile or with aiwcorpo.mcp.adapter.foreign.allow-plain-http=true");
        }
        ForeignMcpClient.endpoint(foreign);
    }

    /**
     * The header must be built from the credential of the user and from nothing else: a format without {@code {credential}} sends one fixed
     * value for everybody (a shared account instead of the identity of the user), and a line break would split the header.
     */
    static void requireSafeFormat(String format) {
        if (format == null || format.indexOf('\r') >= 0 || format.indexOf('\n') >= 0) {
            throw new IllegalStateException("aiwcorpo.mcp.adapter.foreign.authorization-format is not set or has a line break");
        }
        int first = format.indexOf("{credential}");
        if (first < 0 || format.indexOf("{credential}", first + 1) >= 0) {
            throw new IllegalStateException("aiwcorpo.mcp.adapter.foreign.authorization-format must contain {credential} exactly once "
                    + "(the header carries the credential of the calling user)");
        }
    }

    @Bean
    ForeignTools foreignTools(ForeignMcpClient client, AdapterProperties properties) {
        List<ForeignTool> tools = ForeignCatalog.discover(client, properties);
        return new ForeignTools(tools);
    }

    @Bean
    ToolCallbackProvider foreignToolCallbacks(ForeignTools tools, ForeignMcpClient client, AdapterProperties properties,
                                              McpPlatformProperties platform, ObjectProvider<MeterRegistry> meters) {
        List<ToolCallback> callbacks = tools.callbacks(client, platform.system(), properties, meters.getIfAvailable());
        return ToolCallbackProvider.from(callbacks);
    }

    @Bean
    ToolCallInterceptor classificationInterceptor(ObjectProvider<MeterRegistry> meters) {
        return ForeignTools.classification(meters.getIfAvailable());
    }

    @Bean
    ToolErrorMapping foreignErrors() {
        return ForeignTools.errors();
    }
}
