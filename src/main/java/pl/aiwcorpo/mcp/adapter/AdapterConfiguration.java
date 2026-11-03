package pl.aiwcorpo.mcp.adapter;

import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.server.McpSyncServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
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
import pl.aiwcorpo.mcp.adapter.registry.ManifestCheck;
import pl.aiwcorpo.mcp.adapter.registry.ManifestSubmitter;
import pl.aiwcorpo.mcp.adapter.tool.ClassifiedToolRiskRegistry;
import pl.aiwcorpo.mcp.adapter.tool.ForeignTools;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
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

    /** Outside dev the manifest goes over mTLS with a client certificate; an address that is not https or has no bundle would retry for ever. */
    static void requireSafeRegistry(AdapterProperties.Registry registry, Environment environment) {
        URI url = registry.adminUrl();
        if (url == null || environment.acceptsProfiles(Profiles.of(PlatformConfigurationGuard.DEV_PROFILE))) {
            return;
        }
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) {
            throw new IllegalStateException("aiwcorpo.mcp.adapter.registry.admin-url must be an https address without credentials");
        }
        if (registry.sslBundle() == null || registry.sslBundle().isBlank()) {
            throw new IllegalStateException("aiwcorpo.mcp.adapter.registry.ssl-bundle is not set: the registry channel needs the client "
                    + "certificate mcp-adapter-<serverId> (mTLS)");
        }
    }

    /** The certificate of the gateway must name the host it is reached by; set explicitly instead of relying on a default of the HTTP client. */
    private static SSLParameters hostnameChecked() {
        SSLParameters parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return parameters;
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

    /** Replaces the starter's registry (its own bean is conditional on none): see {@link ClassifiedToolRiskRegistry}. */
    @Bean
    ToolRiskRegistry toolRiskRegistry(McpPlatformProperties platform) {
        return new ClassifiedToolRiskRegistry(platform.getTools().getUnclassified());
    }

    @Bean
    ToolCallInterceptor classificationInterceptor(ObjectProvider<MeterRegistry> meters) {
        return ForeignTools.classification(meters.getIfAvailable());
    }

    @Bean
    ToolErrorMapping foreignErrors() {
        return ForeignTools.errors();
    }

    @Bean
    ManifestCheck adapterManifestCheck(ObjectProvider<McpSyncServer> servers, McpPlatformProperties platform, Environment environment) {
        return new ManifestCheck(servers, platform, PlatformConfigurationGuard.devstackProfile(environment));
    }

    @Bean
    ManifestSubmitter manifestSubmitter(AdapterProperties properties, McpPlatformProperties platform, ManifestCheck manifest,
                                        ObjectProvider<SslBundles> bundles, ObjectProvider<MeterRegistry> meters, Environment environment) {
        AdapterProperties.Registry registry = properties.registry();
        requireSafeRegistry(registry, environment);
        HttpClient.Builder builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(registry.requestTimeout());
        if (registry.sslBundle() != null && !registry.sslBundle().isBlank()) {
            builder.sslContext(bundles.getObject().getBundle(registry.sslBundle()).createSslContext());
            builder.sslParameters(hostnameChecked());
        }
        return new ManifestSubmitter(registry, platform.getServerId(), manifest, builder.build(), meters.getIfAvailable());
    }
}
