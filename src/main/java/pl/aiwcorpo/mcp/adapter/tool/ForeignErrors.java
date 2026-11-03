package pl.aiwcorpo.mcp.adapter.tool;

import java.util.Optional;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignCallException;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;

/**
 * What a client is told when the foreign server fails. A denial, a missing issue and an error the server reports all give the
 * one fixed {@code not_found_or_denied}: the adapter cannot tell them apart from what the server says, and a text that varied would
 * tell a missing resource from a forbidden one (CC-146). Only an unreachable or incoherent server is an outage.
 */
final class ForeignErrors implements ToolErrorMapping {

    static final String UNAVAILABLE_TEXT = "The backend system is not available right now. Try again later.";
    static final String OVERSIZE_TEXT = "The tool could not complete the request.";

    @Override
    public Optional<SafeError> map(Throwable ex) {
        if (!(ex instanceof ForeignCallException foreign)) {
            return Optional.empty();
        }
        return Optional.of(switch (foreign.kind()) {
            case DENIED -> new SafeError(ToolErrorCode.NOT_FOUND_OR_DENIED.code(), ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE);
            case UNAVAILABLE -> new SafeError(ToolErrorCode.UPSTREAM_UNAVAILABLE.code(), UNAVAILABLE_TEXT);
            case OVERSIZE -> new SafeError(ToolErrorCode.INTERNAL_ERROR.code(), OVERSIZE_TEXT);
        });
    }
}
