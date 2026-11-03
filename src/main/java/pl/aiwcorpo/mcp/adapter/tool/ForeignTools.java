package pl.aiwcorpo.mcp.adapter.tool;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignMcpClient;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignTool;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.observability.ToolCallInterceptor;

/** The tools the adapter publishes, and the entry points to the package-private callback, result and error classes. */
public record ForeignTools(List<ForeignTool> tools) {

    private static final Logger LOG = LoggerFactory.getLogger(ForeignTools.class);

    /**
     * The callbacks the starter will wrap. Each one is first tried in a registry of its own: whatever the starter refuses at startup (a name
     * with a destructive word under a lower risk, a title it does not accept) leaves that one tool out with a log line, instead of stopping the
     * start of the whole adapter on the word of a foreign server.
     */
    public List<ToolCallback> callbacks(ForeignMcpClient client, String system, AdapterProperties properties, MeterRegistry meters) {
        ForeignResult results = new ForeignResult(properties.limits().resultMaxChars());
        ForeignFailures failures = new ForeignFailures(meters);
        List<ToolCallback> accepted = new ArrayList<>();
        for (ForeignTool tool : tools) {
            ToolCallback callback = new ForeignToolCallback(tool, client, system, properties.foreign().authorizationFormat(), results, failures);
            try {
                new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL).register(callback);
                accepted.add(callback);
            } catch (IllegalStateException ex) {
                LOG.warn("event=foreign_tool_skipped tool=\"{}\" reason=\"refused by the starter's registration checks\"", tool.name());
            }
        }
        if (accepted.isEmpty()) {
            throw new IllegalStateException("the starter accepts none of the " + tools.size() + " tools of the foreign server");
        }
        return accepted;
    }

    public static ToolCallInterceptor classification(MeterRegistry meters) {
        return new ClassificationInterceptor(meters);
    }

    public static ToolErrorMapping errors() {
        return new ForeignErrors();
    }
}
