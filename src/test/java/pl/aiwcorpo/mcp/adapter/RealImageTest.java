package pl.aiwcorpo.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter against the real image ghcr.io/sooperset/mcp-atlassian 0.23.1 (pinned by digest, README "Demo"). Not part of the build:
 * it needs a container started by hand and a fake Jira on the host, and is run with {@code -Preal-image}. The container runs on the
 * default bridge network with port 8000 published on 127.0.0.1 only and JIRA_URL pointing at the fake; it has no access to any
 * stack. The fake Jira accepts the PATs alice-pat and bob-pat as {@code Authorization: Bearer} and knows issue ABC-1.
 *
 * <p>System property {@code mcp.adapter.real.foreign.url} (default http://127.0.0.1:18931) is the published port of the container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class RealImageTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
        registry.add("aiwcorpo.mcp.platform.manifest.accepted-credential-types", () -> "user-secret");
        registry.add("aiwcorpo.mcp.adapter.foreign.url", () -> System.getProperty("mcp.adapter.real.foreign.url", "http://127.0.0.1:18931"));
        registry.add("aiwcorpo.mcp.adapter.foreign.discovery-authorization", () -> "Token discovery-only");
        registry.add("aiwcorpo.mcp.adapter.tools.include",
                () -> "jira_get_issue,jira_search,jira_get_all_projects,jira_get_transitions");
        registry.add("aiwcorpo.mcp.adapter.tools.strip-prefix", () -> "jira_");
    }

    @LocalServerPort
    int port;

    private McpSyncClient client(String pat) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp").openConnectionOnStartup(false)
                .httpRequestCustomizer(AdapterGateway.asGateway(GATEWAY, user -> user.username("jkowalski").groups(List.of("mcp-jira-read", "mcp-jira-write")), pat))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return client;
    }

    private static String text(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        result.content().forEach(content -> {
            if (content instanceof McpSchema.TextContent t) {
                text.append(t.text());
            }
        });
        return text.toString();
    }

    @Test
    void theRealToolsAreListedBoundedAndEachUserReachesJiraWithTheirOwnToken() {
        try (McpSyncClient alice = client("Bearer alice-pat"); McpSyncClient bob = client("Bearer bob-pat")) {
            assertThat(alice.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("get_issue", "search",
                    "get_all_projects", "get_transitions");

            JsonNode forAlice = JSON.readTree(text(alice.callTool(McpSchema.CallToolRequest.builder("get_issue")
                    .arguments(Map.of("issue_key", "ABC-1")).build())));
            JsonNode forBob = JSON.readTree(text(bob.callTool(McpSchema.CallToolRequest.builder("get_issue")
                    .arguments(Map.of("issue_key", "ABC-1")).build())));

            assertThat(forAlice.path("kind").asString()).isEqualTo("untrusted-data");
            assertThat(forAlice.path("text").asString()).contains("Login page returns 500", "Seen as alice");
            assertThat(forBob.path("text").asString()).contains("Seen as bob");
        }
    }

    @Test
    void aTokenJiraDoesNotKnowAndAMissingIssueGiveTheSameConstantAnswer() {
        try (McpSyncClient stranger = client("Bearer mallory-pat"); McpSyncClient alice = client("Bearer alice-pat")) {
            McpSchema.CallToolResult wrongToken = stranger.callTool(McpSchema.CallToolRequest.builder("get_issue")
                    .arguments(Map.of("issue_key", "ABC-1")).build());
            McpSchema.CallToolResult missing = alice.callTool(McpSchema.CallToolRequest.builder("get_issue")
                    .arguments(Map.of("issue_key", "ABC-999")).build());

            assertThat(wrongToken.isError()).isTrue();
            assertThat(text(wrongToken)).contains("\"error\":\"not_found_or_denied\"");
            assertThat(text(missing)).isEqualTo(text(wrongToken));
        }
    }
}
