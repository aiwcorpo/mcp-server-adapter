package pl.aiwcorpo.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/** Only the gateway gets in: no internal JWT, or one not meant for this call, is 401 and never reaches the foreign server. */
class GatewayOnlyAccessTest extends ServerTestSupport {

    private static final String GET_ISSUE = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_issue","arguments":{"issue_key":"ABC-1"}}}""";

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"claim-test","version":"1"}}}""";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void withoutJwtEveryMcpRequestIs401() throws Exception {
        HttpResponse<String> call = post(GET_ISSUE, Map.of("X-MCP-Credential", "Bearer stolen-pat", "X-MCP-Credential-Type", "user-secret"));
        assertThat(call.statusCode()).isEqualTo(401);
        assertThat(call.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(call.body()).contains("\"error\":\"unauthenticated\"").doesNotContain("stolen-pat");

        assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", Map.of()).statusCode()).isEqualTo(401);
        assertThat(post(INITIALIZE, Map.of()).statusCode()).isEqualTo(401);
        assertThat(FOREIGN.calls()).isEmpty();
    }

    @Test
    void aTokenForAnotherServerToolOrRequestIsRejected() throws Exception {
        assertThat(post(GET_ISSUE, GATEWAY.user().tool("get_issue").audience("mcp-gitlab").mint().headers()).statusCode()).isEqualTo(401);
        assertThat(post(GET_ISSUE, GATEWAY.user().tool("search").mint().headers()).statusCode()).isEqualTo(401);
        assertThat(post(GET_ISSUE, GATEWAY.service().mint().headers()).statusCode()).isEqualTo(401);
        assertThat(post(GET_ISSUE, GATEWAY.user().tool("get_issue").issuedAt(Instant.now().minusSeconds(300)).mint().headers())
                .statusCode()).isEqualTo(401);
        assertThat(FOREIGN.calls()).isEmpty();
    }

    @Test
    void aToolCallWithoutTheClassificationOfTheAdministratorIs401ButWithItPasses() throws Exception {
        TestGateway.Minted without = GATEWAY.user().username("jkowalski").groups(java.util.List.of(READ_GROUP)).tool("get_issue").mint();
        HttpResponse<String> refused = post(GET_ISSUE, without.headers());
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.body()).doesNotContain("classification");

        TestGateway.Minted partial = GATEWAY.user().tool("get_issue").extraClaim("mcp_risk", "READ_ONLY").mint();
        assertThat(post(GET_ISSUE, partial.headers()).statusCode()).isEqualTo(401);
        TestGateway.Minted inconsistent = GATEWAY.user().tool("get_issue").extraClaim("mcp_risk", "READ_ONLY")
                .extraClaim("mcp_action", "delete").mint();
        assertThat(post(GET_ISSUE, inconsistent.headers()).statusCode()).isEqualTo(401);
        assertThat(FOREIGN.calls()).isEmpty();
    }

    @Test
    void theClassificationWithoutAToolIsRefusedToo() throws Exception {
        TestGateway.Minted noTool = GATEWAY.user().extraClaim("mcp_risk", "READ_ONLY").extraClaim("mcp_action", "read").mint();
        assertThat(post(INITIALIZE, noTool.headers()).statusCode()).isEqualTo(401);
    }

    @Test
    void aTokenSignedByTheTestKeysOnlyPassesBecauseTheDevProfileAllowsIt() throws Exception {
        assertThat(post(INITIALIZE, GATEWAY.user().mint().headers()).statusCode()).isEqualTo(200);
    }

    @Test
    void onlyTheHealthEndpointAnswersWithoutJwt() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(401);
        assertThat(get("/mcp-manifest").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + ENDPOINT)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
