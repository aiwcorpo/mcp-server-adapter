package pl.aiwcorpo.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.aiwcorpo.mcp.manifest.ManifestRules;
import pl.aiwcorpo.mcp.platform.manifest.ManifestCheck;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import tools.jackson.databind.JsonNode;

/**
 * Contracts 8.0.0 at the sidecar: the foreign server declares resources, prompts, completions and logging (D7: the adapter exposes none of
 * them until an administrator classifies foreign resources, and never prompts), and the principal an agent or a delegated call carries reaches the audit event. Which AI client may call is decided by the gateway: the sidecar takes no policy decisions.
 */
class ToolsOnlyAndAgentsTest extends ServerTestSupport {

    private static final String AGENT_CLIENT = "agent-reports";
    private static final String AGENT_SUB = "3b0f6c52-8f3e-4f0e-b6a2-5d5a1f0e9c11";

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"primitives-test","version":"1"}}}""";

    private static final String GET_ISSUE = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_issue","arguments":{"issue_key":"ABC-1"}}}""";

    @Autowired
    ManifestCheck check;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void theManifestIsBuiltOnContracts8AndOffersNeitherPromptsNorResources() {
        String text = check.manifest().orElseThrow();
        JsonNode manifest = JSON.readTree(text);

        assertThat(manifest.path("contractsVersion").asString()).isEqualTo("8.0.0");
        assertThat(manifest.path("source").asString()).isEqualTo("adapter");
        assertThat(manifest.has("prompts")).as("prompts of an adapter are refused by the schema (maxItems 0)").isFalse();
        assertThat(manifest.has("resources")).isFalse();
        assertThat(manifest.has("resourceTemplates")).isFalse();
        assertThat(manifest.path("tools")).isNotEmpty();
        assertThat(ManifestRules.violations(text)).isEmpty();
    }

    @Test
    void theAdapterDeclaresToolsOnlyAlthoughTheForeignServerDeclaresMore() throws Exception {
        HttpResponse<String> initialize = post(INITIALIZE, GATEWAY.user().mint().headers(), null);

        assertThat(initialize.statusCode()).isEqualTo(200);
        assertThat(initialize.body()).contains("\"tools\"")
                .doesNotContain("\"resources\"", "\"prompts\"", "\"completions\"", "\"logging\"");
    }

    @Test
    void resourcesAndPromptsOfTheForeignServerAreRefusedAndNeverAsked() throws Exception {
        String session = session();
        Map<String, String> requests = Map.of(
                "resources/list", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}",
                "resources/templates/list", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/templates/list\"}",
                "prompts/list", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/list\"}",
                "prompts/get", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/get\",\"params\":{\"name\":\"summarize\"}}",
                "completion/complete", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"completion/complete\",\"params\":{}}",
                "logging/setLevel", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"logging/setLevel\",\"params\":{\"level\":\"debug\"}}");
        for (Map.Entry<String, String> request : requests.entrySet()) {
            HttpResponse<String> answer = post(request.getValue(), GATEWAY.user().mint().headers(), session);

            assertThat(answer.body()).as(request.getKey()).contains("\"code\":-32601").doesNotContain("\"result\"");
        }
        String uri = "jira+file:///docs/fields.md";
        HttpResponse<String> read = post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\",\"params\":{\"uri\":\"" + uri + "\"}}",
                GATEWAY.user().extraClaim("resource", uri).mint().headers(), session);
        assertThat(read.body()).contains("\"code\":-32601");

        assertThat(FOREIGN.unexpectedMethods()).isEmpty();
        assertThat(FOREIGN.calls()).isEmpty();
    }

    @Test
    void aToolCallDoesNotTouchThePrimitivesOfTheForeignServer() throws Exception {
        TestGateway.Token user = GATEWAY.user().tool("get_issue").extraClaim("mcp_risk", "READ_ONLY").extraClaim("mcp_action", "read");
        HttpResponse<String> call = post(GET_ISSUE, withCredential(user.mint().headers()), session());

        assertThat(call.statusCode()).isEqualTo(200);
        assertThat(call.body()).contains("\"result\"");
        assertThat(FOREIGN.calls()).hasSize(1);
        assertThat(FOREIGN.unexpectedMethods()).isEmpty();
    }

    @Test
    void anAgentCallPassesTheVerifierAndItsAuditEventNamesThePrincipalType() throws Exception {
        String session = session();

        List<JsonNode> agent = audit(() -> sendQuietly(classified(GATEWAY.user().client(AGENT_CLIENT)
                .username("service-account-agent-reports").extraClaim("pty", "agent")), session));
        assertThat(FOREIGN.calls()).hasSize(1);
        JsonNode agentCall = toolCall(agent);
        assertThat(agentCall.path("principalType").asString()).isEqualTo("agent");
        assertThat(agentCall.has("actorSub")).isFalse();
        assertThat(agentCall.has("actorClient")).isFalse();

        List<JsonNode> delegated = audit(() -> sendQuietly(classified(GATEWAY.user().client("claude-code")
                .extraClaim("pty", "delegated").extraClaim("act", Map.of("sub", AGENT_SUB, "azp", AGENT_CLIENT))), session));
        JsonNode delegatedCall = toolCall(delegated);
        assertThat(delegatedCall.path("principalType").asString()).isEqualTo("delegated");
        assertThat(delegatedCall.path("actorSub").asString()).isEqualTo(AGENT_SUB);
        assertThat(delegatedCall.path("actorClient").asString()).isEqualTo(AGENT_CLIENT);

        List<JsonNode> human = audit(() -> sendQuietly(classified(GATEWAY.user()), session));
        assertThat(toolCall(human).has("principalType")).as("a human has no principalType in the audit event").isFalse();
    }

    @Test
    void aTokenWhosePrincipalClaimsContradictEachOtherIs401AndNeverReachesTheForeignServer() throws Exception {
        String session = session();
        Map<String, TestGateway.Token> refused = Map.of(
                "pty user", classified(GATEWAY.user().extraClaim("pty", "user")),
                "delegated without act", classified(GATEWAY.user().extraClaim("pty", "delegated")),
                "agent with act", classified(GATEWAY.user().extraClaim("pty", "agent").extraClaim("act", Map.of("sub", AGENT_SUB, "azp", AGENT_CLIENT))),
                "act without pty", classified(GATEWAY.user().extraClaim("act", Map.of("sub", AGENT_SUB, "azp", AGENT_CLIENT))));
        refused.forEach((name, token) -> {
            try {
                assertThat(post(GET_ISSUE, withCredential(token.mint().headers()), session).statusCode()).as(name).isEqualTo(401);
            } catch (Exception ex) {
                throw new AssertionError(name, ex);
            }
        });
        assertThat(FOREIGN.calls()).isEmpty();
    }

    private static TestGateway.Token classified(TestGateway.Token token) {
        return token.username("jkowalski").groups(List.of(READ_GROUP)).tool("get_issue")
                .extraClaim("mcp_risk", "READ_ONLY").extraClaim("mcp_action", "read");
    }

    private static Map<String, String> withCredential(Map<String, String> headers) {
        Map<String, String> all = new java.util.LinkedHashMap<>(headers);
        all.put("X-MCP-Credential", PAT);
        all.put("X-MCP-Credential-Type", "user-secret");
        return all;
    }

    private void sendQuietly(TestGateway.Token token, String session) {
        try {
            post(GET_ISSUE, withCredential(token.mint().headers()), session);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static JsonNode toolCall(List<JsonNode> events) {
        return events.stream().filter(event -> "tool_call".equals(event.path("event").asString())).findFirst().orElseThrow();
    }

    private String session() throws Exception {
        HttpResponse<String> initialize = post(INITIALIZE, GATEWAY.user().mint().headers(), null);
        assertThat(initialize.statusCode()).isEqualTo(200);
        String session = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", GATEWAY.user().mint().headers(), session);
        return session;
    }

    private HttpResponse<String> post(String body, Map<String, String> headers, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + ENDPOINT)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
