package pl.aiwcorpo.mcp.adapter.tool;

import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignCallException;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignMcpClient;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignTool;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;
import pl.aiwcorpo.mcp.platform.guard.RiskClassifiedTool;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;
import pl.aiwcorpo.mcp.platform.security.CredentialTypeCheck;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One tool of the foreign server. The starter wraps it (binding to the caller, argument validation against the bounded schema,
 * audit, error mapping); what is left here is the part only the adapter knows: put the credential of the calling user in
 * the header the foreign server expects, forward the validated arguments and turn the answer into data.
 */
final class ForeignToolCallback implements ToolCallback, RiskClassifiedTool {

    private static final Logger LOG = LoggerFactory.getLogger(ForeignToolCallback.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ForeignTool tool;
    private final ForeignMcpClient client;
    private final String system;
    private final String authorizationFormat;
    private final ForeignResult results;
    private final ForeignFailures failures;
    private final ToolDefinition definition;
    private final Semaphore inFlight;
    private final List<String> acceptedCredentialTypes;

    ForeignToolCallback(ForeignTool tool, ForeignMcpClient client, String system, String authorizationFormat,
                        ForeignResult results, ForeignFailures failures, Semaphore inFlight, List<String> acceptedCredentialTypes) {
        this.tool = tool;
        this.client = client;
        this.system = system;
        this.authorizationFormat = authorizationFormat;
        this.results = results;
        this.failures = failures;
        this.inFlight = inFlight;
        this.acceptedCredentialTypes = List.copyOf(acceptedCredentialTypes);
        this.definition = ToolDefinition.builder().name(tool.name()).description(tool.description())
                .inputSchema(tool.inputSchema().toString()).build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        BackendCredential credential = CredentialContext.require(system);
        // The starter makes this check (CC-166) only in its policy, which receiver-mode adapter turns off; the manifest promises the types.
        String typeHeader = CredentialContext.typeHeader().orElse(null);
        if (!CredentialTypeCheck.admits(CredentialContext.credentialSent(), typeHeader, acceptedCredentialTypes)) {
            LOG.error("event=credential_type_mismatch tool=\"{}\" accepted={} header_present={}", tool.name(), acceptedCredentialTypes,
                    typeHeader != null);
            throw new SanitizedToolException(new McpErrorMapper.SafeError(ToolErrorCode.INTERNAL_ERROR.code(),
                    "The tool could not complete the request."));
        }
        String requestId = CallerContext.current().map(CallerContext::requestId).orElse(null);
        if (!inFlight.tryAcquire()) {
            ForeignCallException busy = new ForeignCallException(ForeignCallException.Kind.UNAVAILABLE, ForeignCallException.Reason.BUSY);
            failures.record(tool.name(), requestId, busy);
            throw busy;
        }
        try {
            JsonNode result = client.callTool(tool.foreignName(), arguments(toolInput), authorization(credential), requestId);
            return results.render(result);
        } catch (ForeignCallException ex) {
            failures.record(tool.name(), requestId, ex);
            if (ex.kind() == ForeignCallException.Kind.UNAUTHORIZED) {
                // The foreign server refused the header itself: the user's credential is missing, expired or in a form it does not take.
                throw new CredentialRequiredException(system);
            }
            throw ex;
        } finally {
            inFlight.release();
        }
    }

    /** The header value for the foreign server; a value with a line break would split the header, so it is refused before it is built. */
    private String authorization(BackendCredential credential) {
        String value = credential.value();
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new ForeignCallException(ForeignCallException.Kind.DENIED, ForeignCallException.Reason.CREDENTIAL_REJECTED);
        }
        return authorizationFormat.replace("{credential}", value);
    }

    private static JsonNode arguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode node = JSON.readTree(toolInput);
            return node.isObject() ? node : JSON.createObjectNode();
        } catch (JacksonException ex) {
            return JSON.createObjectNode();
        }
    }

    @Override
    public RiskLevel riskLevel() {
        return tool.localRisk();
    }

    @Override
    public String title() {
        return tool.title();
    }

    /** The result is one untrusted text, not a list of resources the policy could filter (CC-193: policies are per tool). */
    @Override
    public boolean resultWithoutResources() {
        return true;
    }
}
