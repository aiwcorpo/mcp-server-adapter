package pl.aiwcorpo.mcp.adapter.foreign;

import java.util.Locale;
import java.util.Set;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import tools.jackson.databind.JsonNode;

/**
 * The risk the adapter itself uses for its audit lines, for the hints in its {@code tools/list} and for the local {@code call} check of
 * the starter, read from the hints of the foreign server. The starter wants a risk for every tool and the adapter does not classify
 * (CC-193), so this is a stand-in with one direction of error: unsure means {@code DESTRUCTIVE}.
 *
 * <ul>
 *   <li>{@code readOnlyHint: true} gives {@code READ_ONLY};</li>
 *   <li>an explicit {@code destructiveHint: false} gives {@code UPDATE};</li>
 *   <li>everything else, a missing hint and {@code destructiveHint: true} included, gives {@code DESTRUCTIVE};</li>
 *   <li>a name with a destructive word ({@code delete}, {@code remove}, {@code reset}...) is {@code DESTRUCTIVE} whatever the hints say,
 *       as the starter requires: it refuses to start with such a name under a lower risk.</li>
 * </ul>
 *
 * <p>It does not decide what matters. The gateway authorizes with the classification an administrator gave in the registry and never reads
 * the hints (CC-193, point 5); a tool unclassified there is refused as {@code tool_disabled}. What this adds is a second, weaker check:
 * a tool the server calls read-only needs the read group here, anything else the group of an update or a destructive tool. A lying
 * {@code readOnlyHint} therefore weakens only this second check. When the administrator classifies a tool lower than the local risk,
 * the local check refuses users the gateway let through (fail-closed, visible as {@code access_denied} in the audit of the adapter).
 */
final class LocalRisk {

    /** The words the starter's tripwire ({@code ToolRiskRegistry}) looks for; the catalog also tries each tool in a registry of its own, so a drift of this list cannot stop the start. */
    private static final Set<String> DESTRUCTIVE_WORDS = Set.of("delete", "drop", "remove", "purge", "truncate", "destroy", "wipe",
            "erase", "del", "rm", "revoke", "unlink", "kill", "terminate", "reset", "clear");

    private LocalRisk() {
    }

    static RiskLevel of(JsonNode annotations, String name) {
        RiskLevel fromHints = fromHints(annotations);
        return fromHints != RiskLevel.DESTRUCTIVE && hasDestructiveWord(name) ? RiskLevel.DESTRUCTIVE : fromHints;
    }

    private static RiskLevel fromHints(JsonNode annotations) {
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

    static boolean hasDestructiveWord(String toolName) {
        String spaced = toolName
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([A-Za-z])([0-9])", "$1 $2")
                .toLowerCase(Locale.ROOT);
        for (String word : spaced.split("[^a-z]+")) {
            String singular = word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
            if (DESTRUCTIVE_WORDS.contains(word) || DESTRUCTIVE_WORDS.contains(singular)) {
                return true;
            }
        }
        return false;
    }
}
