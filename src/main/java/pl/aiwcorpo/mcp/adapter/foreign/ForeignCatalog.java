package pl.aiwcorpo.mcp.adapter.foreign;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;
import pl.aiwcorpo.mcp.adapter.schema.SchemaTightener;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads {@code tools/list} of the foreign server at startup and decides which tools the adapter publishes. A tool whose schema
 * cannot be bounded, whose name the contract does not allow or that is not on the configured list is left out and logged; the
 * adapter never publishes a tool it cannot validate the arguments of.
 */
public final class ForeignCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(ForeignCatalog.class);
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");
    private static final int MAX_NAME = 64;
    private static final int MAX_TITLE = 100;

    private ForeignCatalog() {
    }

    /**
     * Waits for the foreign server up to {@code startup-wait}, then builds the catalog.
     *
     * @throws IllegalStateException when the server does not answer in time or no tool can be published
     */
    public static List<ForeignTool> discover(ForeignMcpClient client, AdapterProperties properties) {
        List<ObjectNode> listed = listWithRetry(client, properties);
        List<ForeignTool> tools = build(listed, properties);
        if (tools.isEmpty()) {
            throw new IllegalStateException("none of the " + listed.size() + " tools of the foreign server can be published "
                    + "(see the event=foreign_tool_skipped lines); an adapter without tools registers nothing");
        }
        LOG.info("event=foreign_catalog_ready listed={} published={}", listed.size(), tools.size());
        return List.copyOf(tools);
    }

    static List<ObjectNode> listWithRetry(ForeignMcpClient client, AdapterProperties properties) {
        long deadline = System.nanoTime() + properties.foreign().startupWait().toNanos();
        String discovery = properties.foreign().discoveryAuthorization();
        ForeignCallException last;
        do {
            try {
                return client.listTools(discovery.isEmpty() ? null : discovery);
            } catch (ForeignCallException ex) {
                last = ex;
                LOG.info("event=foreign_not_ready kind={} status={}", ex.kind(), ex.httpStatus());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the foreign server", ex);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("the foreign server did not list its tools within "
                + properties.foreign().startupWait() + " (last: " + last.kind() + ", HTTP " + last.httpStatus() + ")");
    }

    static List<ForeignTool> build(List<ObjectNode> listed, AdapterProperties properties) {
        SchemaTightener tightener = new SchemaTightener(properties.limits());
        Set<String> include = new HashSet<>(properties.tools().include());
        String prefix = properties.tools().stripPrefix();
        Set<String> names = new HashSet<>();
        List<ForeignTool> tools = new ArrayList<>();
        for (ObjectNode tool : listed) {
            String foreignName = tool.path("name").asString("");
            if (!include.isEmpty() && !include.contains(foreignName)) {
                skipped(foreignName, "not in tools.include");
                continue;
            }
            String name = !prefix.isEmpty() && foreignName.startsWith(prefix) && foreignName.length() > prefix.length()
                    ? foreignName.substring(prefix.length()) : foreignName;
            if (name.length() > MAX_NAME || !TOOL_NAME.matcher(name).matches() || name.contains("__")) {
                skipped(foreignName, "the name is not a snake_case tool name of the contract");
                continue;
            }
            if (!names.add(name)) {
                skipped(foreignName, "two tools get the name " + name);
                continue;
            }
            SchemaTightener.Result schema = tightener.tighten(tool.get("inputSchema"));
            if (schema.schema().isEmpty()) {
                skipped(foreignName, String.join("; ", schema.problems()));
                names.remove(name);
                continue;
            }
            if (schema.schema().get().path("properties").has("confirmation_code")) {
                skipped(foreignName, "declares confirmation_code, which belongs to the gateway");
                names.remove(name);
                continue;
            }
            JsonNode annotations = tool.get("annotations");
            String title = titleOf(tool, annotations, name);
            String description = descriptionOf(tool, title, properties.limits().descriptionMaxLength());
            tools.add(new ForeignTool(name, foreignName, title, description, schema.schema().get(), LocalRisk.of(annotations)));
        }
        return tools;
    }

    private static String titleOf(ObjectNode tool, JsonNode annotations, String name) {
        for (String candidate : new String[] {tool.path("title").asString(""),
                annotations == null ? "" : annotations.path("title").asString("")}) {
            String text = candidate.strip();
            if (!text.isEmpty() && text.length() <= MAX_TITLE && TextHygiene.firstProblem(text, true) == null) {
                return text;
            }
        }
        return name;
    }

    /**
     * The description is text of the foreign server that is shown to the model. It is cleaned of control and invisible characters and cut;
     * the registry pins the result by its hash, so an administrator approves the words the model will read.
     */
    private static String descriptionOf(ObjectNode tool, String title, int maxLength) {
        String raw = tool.path("description").asString("");
        String cut = UntrustedText.cut(TextHygiene.normalize(raw), maxLength).strip();
        return cut.isEmpty() ? title : cut;
    }

    private static void skipped(String name, String reason) {
        LOG.warn("event=foreign_tool_skipped tool=\"{}\" reason=\"{}\"", name.replaceAll("[^A-Za-z0-9_.-]", "?"),
                reason.replaceAll("[^\\x20-\\x7E]", "?").replace('"', '\''));
    }
}
