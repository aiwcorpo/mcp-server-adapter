package pl.aiwcorpo.mcp.adapter.registry;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import pl.aiwcorpo.mcp.manifest.DefinitionHash;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.manifest.ManifestGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The C6 manifest of the adapter (CC-193): {@code source: adapter}, entries without {@code risk} and {@code action}, empty
 * {@code resourceRefs}, and {@code definitionHash} over the definition, the system and the credential. Built from the tools as
 * the MCP server lists them, so the hash the registry pins is the hash of exactly what a client is shown; the registry compares it
 * with a later {@code tools/list} of the running adapter. Checked against the schema and the semantic rules before it is sent.
 */
public final class AdapterManifest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AdapterManifest() {
    }

    /**
     * @param published   the tools of the adapter's MCP server
     * @param devEnvironment true for the {@code devstack} profile: the manifest says {@code environment: dev} (CC-138)
     * @throws IllegalStateException when the registry would reject the manifest
     */
    public static String build(McpPlatformProperties platform, List<McpSchema.Tool> published, boolean devEnvironment) {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("serverId", platform.getServerId());
        manifest.put("version", platform.getManifest().getVersion());
        manifest.put("system", platform.system());
        manifest.put("source", "adapter");
        if (devEnvironment) {
            manifest.put("environment", "dev");
        }
        manifest.put("internalUrl", platform.getManifest().getInternalUrl());
        ObjectNode credential = JSON.createObjectNode();
        credential.put("required", platform.getManifest().isCredentialRequired());
        ArrayNode accepted = credential.putArray("acceptedTypes");
        platform.getManifest().getAcceptedCredentialTypes().forEach(accepted::add);
        manifest.set("credential", credential);
        manifest.putArray("resourceTypes");

        List<McpSchema.Tool> sorted = new ArrayList<>(published);
        sorted.sort((a, b) -> a.name().compareTo(b.name()));
        ArrayNode tools = manifest.putArray("tools");
        for (McpSchema.Tool tool : sorted) {
            ObjectNode entry = JSON.createObjectNode();
            entry.put("name", tool.name());
            entry.putArray("resourceRefs");
            JsonNode definition = JSON.valueToTree(tool);
            entry.set("definition", definition);
            ObjectNode hashed = JSON.createObjectNode();
            hashed.putArray("resourceRefs");
            entry.put("definitionHash", DefinitionHash.compute(definition.toString(), hashed.toString(), platform.system(),
                    credential.toString()));
            tools.add(entry);
        }
        String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        ManifestGenerator.check(text, true);
        return text;
    }
}
