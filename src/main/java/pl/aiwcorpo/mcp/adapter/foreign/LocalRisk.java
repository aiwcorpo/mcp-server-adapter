package pl.aiwcorpo.mcp.adapter.foreign;

import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import tools.jackson.databind.JsonNode;

/**
 * The risk the adapter itself uses for audit lines and for the local {@code call} check of the starter, read from the hints of the
 * foreign server. The starter wants a risk for every tool and the adapter does not classify (CC-193), so this is a stand-in with
 * one rule: a hint can only make the local check stricter than "read".
 *
 * <p>It does not decide anything that matters. The gateway authorizes with the classification an administrator gave in the registry
 * and never reads these hints (CC-193, point 5); a tool unclassified there is refused as {@code tool_disabled}. What the adapter
 * adds is a second, weaker check: a tool the server calls read-only needs the read group here, anything else needs the group of
 * an update or a destructive tool. When the administrator classifies a tool lower than the hint suggests, the local check refuses
 * users the gateway let through (fail-closed, visible as {@code access_denied} in the audit of the adapter).
 */
final class LocalRisk {

    private LocalRisk() {
    }

    static RiskLevel of(JsonNode annotations) {
        if (annotations != null && annotations.isObject()) {
            if (annotations.path("destructiveHint").asBoolean(false)) {
                return RiskLevel.DESTRUCTIVE;
            }
            if (annotations.path("readOnlyHint").asBoolean(false)) {
                return RiskLevel.READ_ONLY;
            }
            if (annotations.has("destructiveHint") && annotations.path("destructiveHint").isBoolean()) {
                return RiskLevel.UPDATE;
            }
        }
        return RiskLevel.DESTRUCTIVE;
    }
}
