package pl.aiwcorpo.mcp.adapter.foreign;

import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import tools.jackson.databind.node.ObjectNode;

/**
 * A tool of the foreign server as the adapter publishes it.
 *
 * @param name        the name the platform sees (the foreign name without the configured prefix)
 * @param foreignName the name the foreign server knows
 * @param inputSchema the bounded schema ({@link pl.aiwcorpo.mcp.adapter.schema.SchemaTightener}), not the foreign one
 * @param localRisk   only for the adapter's audit lines and the hints in its tools/list; the classification of the administrator is the one that counts
 */
public record ForeignTool(String name, String foreignName, String title, String description, ObjectNode inputSchema,
                          RiskLevel localRisk) {
}
