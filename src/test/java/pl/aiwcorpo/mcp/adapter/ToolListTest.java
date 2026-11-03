package pl.aiwcorpo.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** What a client sees through the gateway: the narrowed, bounded tools of the foreign server. */
class ToolListTest extends ServerTestSupport {

    private List<McpSchema.Tool> listed() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            return client.listTools().tools();
        }
    }

    @Test
    void publishesOnlyTheConfiguredToolsUnderTheirNamesWithoutThePrefix() {
        assertThat(listed()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("get_issue", "search", "get_all_projects",
                "get_transitions", "add_comment", "transition_issue");
    }

    @Test
    void theToolInTheFixtureThatWasNotIncludedIsNeitherListedNorCallable() {
        assertThat(listed()).extracting(McpSchema.Tool::name).doesNotContain("delete_issue", "jira_delete_issue");
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThatThrownBy(() -> call(client, "delete_issue", Map.of("issue_key", "ABC-1")))
                    .hasMessageContaining("Unknown tool");
        }
        assertThat(FOREIGN.calls()).isEmpty();
    }

    @Test
    void everyDefinitionIsClosedBoundedAndTitled() {
        for (McpSchema.Tool tool : listed()) {
            assertThat(tool.title()).as(tool.name()).isNotBlank();
            assertThat(tool.description()).as(tool.name()).isNotBlank();
            assertClosedAndBounded(JSON.valueToTree(tool.inputSchema()), tool.name());
        }
    }

    @Test
    void theHintsOfTheForeignServerSurviveAsTheLocalRiskOfEachTool() {
        assertThat(tool("get_issue").annotations().readOnlyHint()).isTrue();
        assertThat(tool("get_issue").annotations().destructiveHint()).isFalse();
        assertThat(tool("add_comment").annotations().readOnlyHint()).isFalse();
        assertThat(tool("add_comment").annotations().destructiveHint()).isFalse();
        assertThat(tool("transition_issue").annotations().destructiveHint()).isTrue();
    }

    private McpSchema.Tool tool(String name) {
        return listed().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static void assertClosedAndBounded(JsonNode schema, String where) {
        switch (schema.path("type").asString()) {
            case "object" -> {
                assertThat(schema.path("additionalProperties").asBoolean(true)).as(where).isFalse();
                schema.path("properties").propertyNames()
                        .forEach(name -> assertClosedAndBounded(schema.path("properties").get(name), where + "." + name));
            }
            case "string" -> assertThat(schema.has("maxLength") || schema.has("enum")).as(where).isTrue();
            case "array" -> {
                assertThat(schema.has("maxItems")).as(where).isTrue();
                assertClosedAndBounded(schema.get("items"), where + "[]");
            }
            case "integer", "number" -> assertThat(schema.has("minimum") && schema.has("maximum")).as(where).isTrue();
            default -> { }
        }
    }
}
