package pl.aiwcorpo.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.auth.ReceiverMode;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.manifest.ManifestGenerator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/** The manifest of an adapter, as the starter builds it in {@code receiver-mode: adapter} (CC-193, CC-206). */
class AdapterManifestTest {

    private static McpPlatformProperties adapter(String serverId, String version) {
        McpPlatformProperties platform = new McpPlatformProperties();
        platform.setServerId(serverId);
        platform.getInternalJwt().setReceiverMode(ReceiverMode.ADAPTER);
        platform.getManifest().setVersion(version);
        return platform;
    }

    private static String manifest(McpPlatformProperties platform, McpSchema.Tool tool) {
        return ManifestGenerator.generate(platform, null, List.of(tool), null, true, false);
    }

    /**
     * The definition and the server of the contract vector manifests/valid-adapter (mcp-contracts 6.2.1), whose entry hash the
     * contract states as sha256:382c689a... (definition-hash/get-issue-adapter-without-classification): the adapter must compute the same.
     */
    @Test
    void theHashOfTheContractVectorForAnAdapterEntryIsReproduced() {
        McpPlatformProperties platform = adapter("mcp-jira", "0.3.0");
        platform.getManifest().setInternalUrl("https://mcp-jira.internal:8443/mcp");
        platform.getManifest().setAcceptedCredentialTypes(List.of("user-oauth", "user-secret"));
        Map<String, Object> schema = Fixtures.JSON.readValue("""
                {"type":"object","properties":{"issueKey":{"type":"string","description":"Issue key, e.g. ABC-123",
                "pattern":"^[A-Z][A-Z0-9]+-[0-9]+$","maxLength":32}},"required":["issueKey"],"additionalProperties":false}""",
                new TypeReference<Map<String, Object>>() { });
        McpSchema.Tool tool = McpSchema.Tool.builder().name("get_issue").title("Get issue")
                .description("Returns one Jira issue by key.").inputSchema(Fixtures.JSON.convertValue(schema, McpSchema.JsonSchema.class))
                .annotations(new McpSchema.ToolAnnotations(null, true, null, null, null, null)).build();

        JsonNode manifest = Fixtures.JSON.readTree(manifest(platform, tool));

        assertThat(manifest.at("/tools/0/definitionHash").asString())
                .isEqualTo("sha256:382c689a44ccadf3874aeea0671fe8266929ddbcf22ac8691dbdcfb13cfc46c7");
        assertThat(manifest.at("/tools/0").propertyNames()).containsExactlyInAnyOrder("name", "resourceRefs", "definition", "definitionHash");
    }

    @Test
    void aSystemOutsideAnyListIsAcceptedAndTheManifestHasNoPolicies() {
        McpPlatformProperties platform = adapter("mcp-notes", "2.0.0");
        platform.getManifest().setAcceptedCredentialTypes(List.of("user-secret"));
        McpSchema.Tool tool = McpSchema.Tool.builder().name("list_notes").title("List notes").description("Returns the notes of the user.")
                .inputSchema(Fixtures.JSON.readValue("""
                        {"type":"object","properties":{},"additionalProperties":false}""", McpSchema.JsonSchema.class))
                .annotations(new McpSchema.ToolAnnotations(null, true, null, null, null, null)).build();

        JsonNode manifest = Fixtures.JSON.readTree(manifest(platform, tool));

        assertThat(manifest.path("system").asString()).isEqualTo("notes");
        assertThat(manifest.path("source").asString()).isEqualTo("adapter");
        assertThat(manifest.has("internalUrl")).as("informational and optional (CC-249)").isFalse();
        assertThat(manifest.path("resourceTypes")).isEmpty();
        assertThat(manifest.has("policies")).isFalse();
        assertThat(manifest.has("policiesHash")).isFalse();
    }
}
