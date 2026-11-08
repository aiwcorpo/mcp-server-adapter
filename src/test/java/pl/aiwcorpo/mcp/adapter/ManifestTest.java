package pl.aiwcorpo.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.aiwcorpo.mcp.manifest.DefinitionHash;
import pl.aiwcorpo.mcp.manifest.ManifestRules;
import pl.aiwcorpo.mcp.platform.manifest.ManifestCheck;
import tools.jackson.databind.JsonNode;

/** The manifest the adapter serves for the gateway to pull: the form of CC-193 and exactly what tools/list shows. */
class ManifestTest extends ServerTestSupport {

    @Autowired
    ManifestCheck check;

    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

    private java.net.http.HttpResponse<String> get(String path, java.util.Map<String, String> headers) throws Exception {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl() + path)).GET();
        headers.forEach(request::header);
        return http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void isServedAtTheWellKnownPathWithoutAuthenticationAndIsTheDocumentTheCheckBuilt() throws Exception {
        String path = "/.well-known/mcp-manifest";

        java.net.http.HttpResponse<String> served = get(path, java.util.Map.of());

        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(JSON.readTree(served.body())).isEqualTo(manifest());
        assertThat(JSON.readTree(served.body()).path("source").asString()).isEqualTo("adapter");
        assertThat(get(path, java.util.Map.of("Authorization", "Bearer not-a-jwt")).statusCode()).as("a header that arrives is ignored").isEqualTo(200);
    }

    private JsonNode manifest() {
        return JSON.readTree(check.manifest().orElseThrow());
    }

    @Test
    void isTheManifestOfAnAdapterWithoutClassification() {
        JsonNode manifest = manifest();

        assertThat(manifest.path("source").asString()).isEqualTo("adapter");
        assertThat(manifest.path("serverId").asString()).isEqualTo("mcp-jira");
        assertThat(manifest.path("system").asString()).isEqualTo("jira");
        assertThat(manifest.path("version").asString()).matches("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");
        assertThat(manifest.has("environment")).as("production unless the devstack profile is active").isFalse();
        assertThat(manifest.path("credential").path("required").asBoolean()).isTrue();
        assertThat(manifest.path("credential").path("acceptedTypes")).extracting(JsonNode::asString).containsExactly("user-secret");
        assertThat(manifest.path("resourceTypes")).isEmpty();
        assertThat(manifest.has("policies")).isFalse();
        assertThat(manifest.has("policiesHash")).isFalse();
        assertThat(manifest.path("tools")).hasSize(6);
        for (JsonNode tool : manifest.path("tools")) {
            assertThat(tool.has("risk")).as(tool.path("name").asString() + " risk").isFalse();
            assertThat(tool.has("action")).as(tool.path("name").asString() + " action").isFalse();
            assertThat(tool.path("resourceRefs")).isEmpty();
            assertThat(tool.path("definitionHash").asString()).startsWith("sha256:");
        }
    }

    @Test
    void passesTheRulesTheGatewayAppliesAndTheHashesAreTheContractsOwn() {
        String text = check.manifest().orElseThrow();

        assertThat(ManifestRules.violations(text)).isEmpty();
        for (JsonNode tool : manifest().path("tools")) {
            String name = tool.path("name").asString();
            assertThat(tool.path("definitionHash").asString()).as(name).isEqualTo(DefinitionHash.ofManifestTool(text, name));
        }
    }

    @Test
    void theDefinitionsAreWhatAClientIsShown() {
        List<McpSchema.Tool> listed;
        try (McpSyncClient client = clientWithCredential(PAT)) {
            listed = client.listTools().tools();
        }
        JsonNode manifest = manifest();

        assertThat(manifest.path("tools")).hasSameSizeAs(listed);
        for (McpSchema.Tool tool : listed) {
            JsonNode entry = null;
            for (JsonNode candidate : manifest.path("tools")) {
                if (candidate.path("name").asString().equals(tool.name())) {
                    entry = candidate;
                }
            }
            assertThat(entry).as(tool.name()).isNotNull();
            JsonNode definition = entry.path("definition");
            assertThat(definition.path("name").asString()).isEqualTo(tool.name());
            assertThat(definition.path("title").asString()).isEqualTo(tool.title());
            assertThat(definition.path("description").asString()).isEqualTo(tool.description());
            assertThat(definition.path("inputSchema")).isEqualTo(JSON.valueToTree(tool.inputSchema()));
            assertThat(definition.path("annotations")).isEqualTo(JSON.valueToTree(tool.annotations()));
        }
    }

    @Test
    void theToolsAreSortedAndTheHashDoesNotDependOnTheLocalRisk() {
        JsonNode manifest = manifest();
        List<String> names = manifest.path("tools").valueStream().map(tool -> tool.path("name").asString()).toList();
        assertThat(names).isSorted();

        JsonNode tool = manifest.path("tools").get(0);
        String text = check.manifest().orElseThrow();
        var classified = (tools.jackson.databind.node.ObjectNode) JSON.readTree(text);
        ((tools.jackson.databind.node.ObjectNode) classified.path("tools").get(0)).put("risk", "DESTRUCTIVE").put("action", "delete");
        assertThat(DefinitionHash.ofManifestTool(classified.toString(), tool.path("name").asString()))
                .as("CC-206: the hash of an adapter entry never covers risk and action")
                .isEqualTo(tool.path("definitionHash").asString());
    }
}
