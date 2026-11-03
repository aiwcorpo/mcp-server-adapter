package pl.aiwcorpo.mcp.adapter;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.util.function.Consumer;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway as it speaks to the sidecar of an adapter (CC-226): TestGateway's tokens, and in a {@code tools/call} the classification of the
 * administrator as {@code mcp_risk} and {@code mcp_action}. The gateway sends them only with a tool, so the other requests (initialize,
 * tools/list) carry none.
 */
public final class AdapterGateway {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AdapterGateway() {
    }

    /** As the gateway sends it after CC-227: READ_ONLY and read. */
    public static McpSyncHttpClientRequestCustomizer asGateway(TestGateway gateway, Consumer<TestGateway.Token> user, String credential) {
        return asGateway(gateway, user, credential, "READ_ONLY", "read");
    }

    /** {@code risk} null sends a {@code tools/call} without the classification (an older gateway). */
    public static McpSyncHttpClientRequestCustomizer asGateway(TestGateway gateway, Consumer<TestGateway.Token> user, String credential,
                                                               String risk, String action) {
        return (request, method, uri, body, context) -> {
            TestGateway.Token token = gateway.user();
            user.accept(token);
            String tool = toolCalled(body);
            if (tool != null) {
                token.tool(tool);
                if (risk != null) {
                    token.extraClaim("mcp_risk", risk).extraClaim("mcp_action", action);
                }
            }
            token.mint().headers().forEach(request::setHeader);
            if (credential != null) {
                request.setHeader("X-MCP-Credential", credential);
                request.setHeader("X-MCP-Credential-Type", "user-secret");
            }
        };
    }

    private static String toolCalled(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode message = JSON.readTree(body);
        return message.isObject() && "tools/call".equals(message.path("method").asString(null))
                ? message.path("params").path("name").asString(null) : null;
    }
}
