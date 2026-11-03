package pl.aiwcorpo.mcp.adapter.foreign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;
import pl.aiwcorpo.mcp.adapter.Fixtures;
import pl.aiwcorpo.mcp.adapter.StubForeignServer;
import pl.aiwcorpo.mcp.adapter.StubForeignServer.Reply;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ForeignMcpClientTest {

    private StubForeignServer stub;

    @BeforeEach
    void start() {
        stub = new StubForeignServer().tools(Fixtures.atlassianTools());
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private ForeignMcpClient client(DataSize maxResponse) {
        AdapterProperties.Foreign foreign = new AdapterProperties.Foreign(URI.create(stub.url()), "/mcp", "Token {credential}", "",
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5), maxResponse, true, null);
        return new ForeignMcpClient(ForeignMcpClient.httpClient(foreign, null), foreign);
    }

    private ForeignMcpClient client() {
        return client(DataSize.ofMegabytes(1));
    }

    private static JsonNode args() {
        return Fixtures.tree("{\"issue_key\":\"ABC-1\"}");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void listsToolsFromAnEventStreamAndFromPlainJson(boolean eventStream) {
        stub.eventStream(eventStream);

        List<ObjectNode> tools = client().listTools("Token discovery");

        assertThat(tools).extracting(tool -> tool.get("name").asString()).contains("jira_get_issue", "jira_search");
        assertThat(stub.authorizations()).isNotEmpty().allMatch("Token discovery"::equals);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void callsAToolWithTheCredentialOfTheUserAndTheRequestId(boolean eventStream) {
        stub.eventStream(eventStream).onCall(call -> Reply.text("{\"key\":\"ABC-1\"}"));

        JsonNode result = client().callTool("jira_get_issue", args(), "Token alice-pat", "req-77");

        assertThat(result.at("/content/0/text").asString()).isEqualTo("{\"key\":\"ABC-1\"}");
        assertThat(stub.calls()).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("jira_get_issue");
            assertThat(call.arguments().path("issue_key").asString()).isEqualTo("ABC-1");
            assertThat(call.authorization()).isEqualTo("Token alice-pat");
            assertThat(call.requestId()).isEqualTo("req-77");
            assertThat(call.sessionId()).startsWith("stub-session-");
        });
    }

    @Test
    void everyCallOpensItsOwnSessionAndClosesIt() {
        ForeignMcpClient client = client();

        client.callTool("jira_get_issue", args(), "Token alice-pat", "r1");
        client.callTool("jira_get_issue", args(), "Token bob-pat", "r2");

        assertThat(stub.calls()).extracting(StubForeignServer.Call::sessionId).doesNotHaveDuplicates();
        assertThat(stub.calls()).extracting(StubForeignServer.Call::authorization).containsExactly("Token alice-pat", "Token bob-pat");
        assertThat(stub.deletedSessions()).hasSize(2);
    }

    @Test
    void anErrorResultAndAJsonRpcErrorAreDenied() {
        stub.onCall(call -> Reply.errorResult("Issue does not exist: ABC-404 at https://jira.corp.test"));
        assertThatThrownBy(() -> client().callTool("jira_get_issue", args(), "Token a", "r"))
                .isInstanceOfSatisfying(ForeignCallException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(ForeignCallException.Kind.DENIED);
                    assertThat(ex.getMessage()).doesNotContain("ABC-404", "jira.corp.test");
                });

        stub.onCall(call -> Reply.rpcError(-32602));
        assertThatThrownBy(() -> client().callTool("jira_get_issue", args(), "Token a", "r"))
                .isInstanceOfSatisfying(ForeignCallException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(ForeignCallException.Kind.DENIED));
    }

    @Test
    void unauthorizedAndForbiddenAnswersAreDeniedAndServerErrorsAreUnavailable() {
        stub.onCall(call -> Reply.status(403));
        assertThatThrownBy(() -> client().callTool("jira_get_issue", args(), "Token a", "r"))
                .isInstanceOfSatisfying(ForeignCallException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(ForeignCallException.Kind.DENIED);
                    assertThat(ex.httpStatus()).isEqualTo(403);
                });

        stub.onCall(call -> Reply.status(500));
        assertThatThrownBy(() -> client().callTool("jira_get_issue", args(), "Token a", "r"))
                .isInstanceOfSatisfying(ForeignCallException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(ForeignCallException.Kind.UNAVAILABLE));
    }

    @Test
    void aRefusedConnectionIsUnavailable() {
        ForeignMcpClient client = client();
        stub.close();

        assertThatThrownBy(() -> client.callTool("jira_get_issue", args(), "Token a", "r"))
                .isInstanceOfSatisfying(ForeignCallException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(ForeignCallException.Kind.UNAVAILABLE));
    }

    @Test
    void aRedirectIsNotFollowedSoTheCredentialStaysAtTheConfiguredAddress() {
        try (StubForeignServer elsewhere = new StubForeignServer().requireAuthorization(false)) {
            stub.redirectCallsTo(elsewhere.url() + "/mcp");

            assertThatThrownBy(() -> client().callTool("jira_get_issue", args(), "Token alice-pat", "r"))
                    .isInstanceOf(ForeignCallException.class);

            assertThat(elsewhere.authorizations()).isEmpty();
        }
    }

    @Test
    void aResponseOverTheLimitIsRefused() {
        stub.onCall(call -> Reply.text("x".repeat(5000)));

        assertThatThrownBy(() -> client(DataSize.ofBytes(2000)).callTool("jira_get_issue", args(), "Token a", "r"))
                .isInstanceOfSatisfying(ForeignCallException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(ForeignCallException.Kind.OVERSIZE));
    }

    @Test
    void aServerThatRefusesTheHeaderlessDiscoveryIsReportedAsDenied() {
        assertThatThrownBy(() -> client().listTools(null)).isInstanceOfSatisfying(ForeignCallException.class, ex -> {
            assertThat(ex.kind()).isEqualTo(ForeignCallException.Kind.UNAUTHORIZED);
            assertThat(ex.reason()).isEqualTo(ForeignCallException.Reason.HTTP_401);
            assertThat(ex.httpStatus()).isEqualTo(401);
        });
    }

    private ForeignMcpClient clientWithTimeout(Duration timeout) {
        AdapterProperties.Foreign foreign = new AdapterProperties.Foreign(URI.create(stub.url()), "/mcp", "Token {credential}", "",
                Duration.ofSeconds(2), timeout, Duration.ofSeconds(5), DataSize.ofMegabytes(1), true, null);
        return new ForeignMcpClient(ForeignMcpClient.httpClient(foreign, null), foreign);
    }

    @Test
    void anEventStreamThatNeverAnswersEndsAtTheDeadlineOfTheExchange() {
        stub.stream(StubForeignServer.Stream.SILENT_FOR_EVER);
        long start = System.nanoTime();

        assertThatThrownBy(() -> clientWithTimeout(Duration.ofSeconds(1)).callTool("jira_get_issue", args(), "Token a", "r"))
                .isInstanceOfSatisfying(ForeignCallException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(ForeignCallException.Kind.UNAVAILABLE);
                    assertThat(ex.reason()).isEqualTo(ForeignCallException.Reason.TIMEOUT);
                });

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void aStreamThatStaysOpenAfterTheResponseCostsNothing() {
        stub.stream(StubForeignServer.Stream.OPEN_AFTER_RESPONSE).onCall(call -> Reply.text("done"));
        long start = System.nanoTime();

        JsonNode result = clientWithTimeout(Duration.ofSeconds(5)).callTool("jira_get_issue", args(), "Token a", "r");

        assertThat(result.at("/content/0/text").asString()).isEqualTo("done");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aStreamThatTrickleBytesForeverIsCutAtTheDeadlineToo() {
        stub.stream(StubForeignServer.Stream.TRICKLE_FOR_EVER);

        assertThatThrownBy(() -> clientWithTimeout(Duration.ofSeconds(1)).callTool("jira_get_issue", args(), "Token a", "r"))
                .isInstanceOfSatisfying(ForeignCallException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(ForeignCallException.Reason.TIMEOUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id\r\nX-Injected: 1", "has space", ""})
    void aSessionIdThatCannotBeSentBackIsRefused(String sessionId) {
        stub.sessionId(sessionId.isEmpty() ? "x".repeat(300) : sessionId);

        assertThatThrownBy(() -> client().callTool("jira_get_issue", args(), "Token a", "r"))
                .isInstanceOfSatisfying(ForeignCallException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(ForeignCallException.Kind.UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"//other-host/mcp", "http://other-host/mcp", "mcp", "/a/../b", "/mcp?x=1", "/mcp#f", "/ mcp"})
    void aPathThatLeavesTheForeignServerIsRefused(String path) {
        AdapterProperties.Foreign foreign = new AdapterProperties.Foreign(URI.create("https://mcp-atlassian.internal:8443"), path,
                "Token {credential}", "", Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(1),
                false, null);

        assertThatThrownBy(() -> ForeignMcpClient.endpoint(foreign)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOrdinaryPathStaysOnTheBaseAddress() {
        AdapterProperties.Foreign foreign = new AdapterProperties.Foreign(URI.create("https://mcp-atlassian.internal:8443"), "/api/mcp",
                "Token {credential}", "", Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(1),
                false, null);

        assertThat(ForeignMcpClient.endpoint(foreign)).hasToString("https://mcp-atlassian.internal:8443/api/mcp");
    }
}
