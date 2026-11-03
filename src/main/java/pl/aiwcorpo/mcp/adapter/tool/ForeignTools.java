package pl.aiwcorpo.mcp.adapter.tool;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignMcpClient;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignTool;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;

/** The tools the adapter publishes, and the entry points to the package-private callback, result and error classes. */
public record ForeignTools(List<ForeignTool> tools) {

    public List<ToolCallback> callbacks(ForeignMcpClient client, String system, AdapterProperties properties) {
        ForeignResult results = new ForeignResult(properties.limits().resultMaxChars());
        return tools.stream().<ToolCallback>map(tool -> new ForeignToolCallback(tool, client, system,
                properties.foreign().authorizationFormat(), results)).toList();
    }

    public static ToolErrorMapping errors() {
        return new ForeignErrors();
    }
}
