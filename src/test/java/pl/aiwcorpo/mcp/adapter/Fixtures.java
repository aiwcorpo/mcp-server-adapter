package pl.aiwcorpo.mcp.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class Fixtures {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private Fixtures() {
    }

    /** tools/list of mcp-atlassian 0.23.1 as the real image answered it (ENABLED_TOOLS narrowed to the seven Jira tools used in the demo). */
    public static List<ObjectNode> atlassianTools() {
        try (InputStream stream = Fixtures.class.getResourceAsStream("/mcp-atlassian-0.23.1-tools.json")) {
            List<ObjectNode> tools = new ArrayList<>();
            JSON.readTree(stream).forEach(tool -> tools.add((ObjectNode) tool));
            return tools;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static AdapterProperties.Limits limits() {
        return new AdapterProperties.Limits(4096, java.util.Map.of("body", 32768, "jql", 2048), 100, -2147483648L,
                2147483647L, 2000, 1000, 256, 2000, 100000, 8);
    }

    public static JsonNode tree(String json) {
        return JSON.readTree(json);
    }
}
