package pl.aiwcorpo.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole adapter over HTTP, with TestGateway in place of the gateway, {@link StubForeignServer} in place of the foreign
 * server and {@link StubRegistry} in place of the registry. Shared by the test classes so they run against one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
abstract class ServerTestSupport {

    static final String SERVER_ID = "mcp-jira";
    static final String SYSTEM = "jira";
    static final String ENDPOINT = "/mcp";
    static final String PAT_VALUE = "jira-pat-of-jkowalski-7f3a";
    static final String PAT = "Bearer " + PAT_VALUE;
    static final String DISCOVERY = "Token discovery-only";
    static final JsonMapper JSON = JsonMapper.builder().build();

    static final TestGateway GATEWAY = TestGateway.start(SERVER_ID);
    static final StubForeignServer FOREIGN = new StubForeignServer().tools(Fixtures.atlassianTools());
    static final StubRegistry REGISTRY = new StubRegistry();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
        registry.add("aiwcorpo.mcp.platform.manifest.accepted-credential-types", () -> "user-secret");
        registry.add("aiwcorpo.mcp.adapter.foreign.url", FOREIGN::url);
        registry.add("aiwcorpo.mcp.adapter.foreign.discovery-authorization", () -> DISCOVERY);
        registry.add("aiwcorpo.mcp.adapter.foreign.startup-wait", () -> "10s");
        registry.add("aiwcorpo.mcp.adapter.foreign.max-response-size", () -> "64KB");
        registry.add("aiwcorpo.mcp.adapter.tools.include",
                () -> "jira_get_issue,jira_search,jira_get_all_projects,jira_get_transitions,jira_add_comment,jira_transition_issue");
        registry.add("aiwcorpo.mcp.adapter.tools.strip-prefix", () -> "jira_");
        registry.add("aiwcorpo.mcp.adapter.limits.result-max-chars", () -> "2000");
        registry.add("aiwcorpo.mcp.adapter.registry.admin-url", REGISTRY::url);
        registry.add("aiwcorpo.mcp.adapter.registry.retry-interval", () -> "200ms");
        registry.add("aiwcorpo.mcp.adapter.registry.max-retry-interval", () -> "400ms");
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void resetForeign() {
        FOREIGN.reset();
    }

    @AfterAll
    static void noop() {
        // Servers and TestGateway live as long as the JVM: the Spring context is cached across the test classes.
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    static final String READ_GROUP = "mcp-jira-read";
    static final String WRITE_GROUP = "mcp-jira-write";

    static void reader(TestGateway.Token user) {
        user.username("jkowalski").groups(List.of(READ_GROUP));
    }

    static void editor(TestGateway.Token user) {
        user.username("jkowalski").groups(List.of(READ_GROUP, WRITE_GROUP));
    }

    McpSyncClient clientWithCredential(String credential) {
        return clientWithToken(ServerTestSupport::editor, credential);
    }

    McpSyncClient clientWithToken(Consumer<TestGateway.Token> user, String credential) {
        return clientClassifiedAs(user, credential, "READ_ONLY", "read");
    }

    /** A client whose tools/call carry the given classification of the administrator; {@code risk} null leaves it out. */
    McpSyncClient clientClassifiedAs(Consumer<TestGateway.Token> user, String credential, String risk, String action) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint(ENDPOINT)
                .openConnectionOnStartup(false)
                .httpRequestCustomizer(AdapterGateway.asGateway(GATEWAY, user, credential, risk, action))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
    }

    static String text(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        result.content().forEach(content -> {
            if (content instanceof McpSchema.TextContent t) {
                text.append(t.text());
            }
        });
        return text.toString();
    }

    static JsonNode json(McpSchema.CallToolResult result) {
        return JSON.readTree(text(result));
    }

    static void assertError(McpSchema.CallToolResult result, String code) {
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("\"error\":\"" + code + "\"");
    }

    /** The audit events (C8) the starter wrote while the block ran. */
    static List<JsonNode> audit(Runnable block) {
        Logger logger = (Logger) LoggerFactory.getLogger("pl.aiwcorpo.mcp.audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            block.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(event -> JSON.readTree(event.getFormattedMessage())).toList();
    }
}
