package pl.aiwcorpo.mcp.adapter.tool;

import java.util.Locale;
import java.util.Optional;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolAction;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;

/**
 * The starter's registry, with one change: while a call is being decided, the risk and the action of the tool are the classification
 * the administrator gave in the registry and the gateway put in the token ({@code mcp_risk}, {@code mcp_action}, CC-226), not the
 * stand-in worked out from the hints of the foreign server.
 *
 * <p>The starter asks the registry for the risk of the tool when it builds the Cedar request of the local {@code call} check and when
 * it writes a refusal to the audit. Those happen on the request thread of a call, where the caller is known, so they get the
 * classification. Everything that asks outside a call (the hints and the title put in {@code tools/list} at startup, hence the manifest and its
 * hash) gets the stand-in, which is the only place the starter still needs it.
 */
public final class ClassifiedToolRiskRegistry extends ToolRiskRegistry {

    public ClassifiedToolRiskRegistry(Unclassified unclassified) {
        super(unclassified);
    }

    @Override
    public Optional<RiskLevel> riskOf(String toolName) {
        return classified(toolName).map(classification -> classification.risk).or(() -> super.riskOf(toolName));
    }

    @Override
    public Optional<ToolAction> actionOf(String toolName) {
        return classified(toolName).map(classification -> classification.action).or(() -> super.actionOf(toolName));
    }

    private record Classification(RiskLevel risk, ToolAction action) {
    }

    private static Optional<Classification> classified(String toolName) {
        return CallerContext.current().filter(caller -> toolName.equals(caller.tool()) && caller.mcpRisk() != null
                && caller.mcpAction() != null).flatMap(caller -> {
            try {
                return Optional.of(new Classification(RiskLevel.valueOf(caller.mcpRisk()),
                        ToolAction.valueOf(caller.mcpAction().toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException ex) {
                // The verifier has checked the pair against the contract; a value this build does not know falls back to the stand-in.
                return Optional.empty();
            }
        });
    }
}
