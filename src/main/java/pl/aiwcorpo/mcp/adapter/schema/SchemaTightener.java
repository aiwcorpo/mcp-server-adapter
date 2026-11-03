package pl.aiwcorpo.mcp.adapter.schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the {@code inputSchema} a foreign server publishes into one the platform accepts: closed and bounded at every level,
 * without combinators and references (CC-078, CC-123, rule {@code input-schema-bounded} of the manifest).
 *
 * <p>The foreign server is the author of the schema and does not write it for this contract, so the adapter builds the schema it
 * publishes and validates arguments against it before forwarding. Only keywords that carry a restriction the platform can read
 * are kept (a whitelist: a keyword nobody reviewed is not passed on). Where the foreign schema states no limit, the configured
 * one is added; where it cannot be expressed (an open map, a union of types, a recursive reference) the tool is refused, never
 * published with a weaker schema, and the reason names the path of the property, never a value.
 */
public final class SchemaTightener {

    /** Same depth as the starter's rule; a deeper schema is refused here so the startup check of the manifest does not stop the server. */
    private static final int MAX_DEPTH = 8;
    private static final int MAX_REF_DEPTH = 16;
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "array", "object");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AdapterProperties.Limits limits;

    public SchemaTightener(AdapterProperties.Limits limits) {
        this.limits = limits;
    }

    /** The outcome: a schema, or the reasons it cannot be bounded. */
    public record Result(Optional<ObjectNode> schema, List<String> problems) {
    }

    public Result tighten(JsonNode foreign) {
        List<String> problems = new ArrayList<>();
        if (foreign == null || !foreign.isObject()) {
            return new Result(Optional.empty(), List.of("the arguments: no object schema"));
        }
        JsonNode root = foreign;
        ObjectNode result = visit(root, root, "", 0, 0, problems);
        if (result == null || !problems.isEmpty()) {
            return new Result(Optional.empty(), List.copyOf(problems));
        }
        if (!"object".equals(result.path("type").asString(null))) {
            return new Result(Optional.empty(), List.of("the arguments: the root of the schema must be an object"));
        }
        return new Result(Optional.of(result), List.of());
    }

    private ObjectNode visit(JsonNode schema, JsonNode root, String path, int depth, int refDepth, List<String> problems) {
        String where = path.isEmpty() ? "the arguments" : path;
        if (depth > MAX_DEPTH) {
            problems.add(where + ": nested deeper than " + MAX_DEPTH + " levels");
            return null;
        }
        if (!schema.isObject()) {
            problems.add(where + ": a boolean schema accepts any value");
            return null;
        }
        ObjectNode flat = flatten(schema, root, where, refDepth, problems);
        if (flat == null) {
            return null;
        }
        String type = typeOf(flat, where, problems);
        if (type == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("type", type);
        copyText(flat, out, "description");
        copyFixed(flat, out);
        return switch (type) {
            case "string" -> string(flat, out, path);
            case "integer", "number" -> number(flat, out, type);
            case "array" -> array(flat, out, root, path, depth, refDepth, problems);
            case "object" -> object(flat, out, root, path, depth, refDepth, problems);
            default -> out;
        };
    }

    /** Resolves a local {@code $ref}, drops the null branch of a nullable union and merges a single remaining alternative. */
    private ObjectNode flatten(JsonNode schema, JsonNode root, String where, int refDepth, List<String> problems) {
        ObjectNode merged = JSON.createObjectNode();
        JsonNode current = schema;
        for (int hop = 0; hop <= MAX_REF_DEPTH; hop++) {
            current.properties().forEach(entry -> {
                if (!merged.has(entry.getKey())) {
                    merged.set(entry.getKey(), entry.getValue());
                }
            });
            JsonNode next = null;
            if (current.has("$ref")) {
                next = resolve(current.path("$ref").asString(""), root);
                if (next == null) {
                    problems.add(where + ": a reference that is not local or does not resolve");
                    return null;
                }
            } else {
                for (String keyword : List.of("anyOf", "oneOf", "allOf")) {
                    if (current.has(keyword)) {
                        JsonNode only = single(current.get(keyword), keyword);
                        if (only == null) {
                            problems.add(where + ": " + keyword + " offers more than one non-null alternative (a union cannot be bounded)");
                            return null;
                        }
                        next = only;
                    }
                }
            }
            if (next == null) {
                merged.remove("$ref");
                merged.remove("anyOf");
                merged.remove("oneOf");
                merged.remove("allOf");
                return merged;
            }
            if (!next.isObject()) {
                problems.add(where + ": an alternative that is not a schema");
                return null;
            }
            merged.remove("$ref");
            merged.remove("anyOf");
            merged.remove("oneOf");
            merged.remove("allOf");
            current = next;
        }
        problems.add(where + ": references nested deeper than " + MAX_REF_DEPTH + " (recursive?)");
        return null;
    }

    private static JsonNode single(JsonNode alternatives, String keyword) {
        if (!alternatives.isArray()) {
            return null;
        }
        List<JsonNode> kept = new ArrayList<>();
        for (JsonNode alternative : alternatives) {
            boolean isNull = alternative.isObject() && "null".equals(alternative.path("type").asString(null))
                    && alternative.size() == 1;
            if (!isNull) {
                kept.add(alternative);
            }
        }
        return kept.size() == 1 ? kept.getFirst() : null;
    }

    private static JsonNode resolve(String ref, JsonNode root) {
        if (!ref.startsWith("#/")) {
            return null;
        }
        JsonNode node = root;
        for (String part : ref.substring(2).split("/")) {
            node = node.path(part.replace("~1", "/").replace("~0", "~"));
            if (node.isMissingNode()) {
                return null;
            }
        }
        return node;
    }

    private static String typeOf(ObjectNode flat, String where, List<String> problems) {
        JsonNode type = flat.get("type");
        if (type != null && type.isArray()) {
            List<String> kept = new ArrayList<>();
            type.forEach(t -> {
                if (!"null".equals(t.asString(""))) {
                    kept.add(t.asString(""));
                }
            });
            if (kept.size() != 1) {
                problems.add(where + ": a union of types cannot be bounded");
                return null;
            }
            return checkType(kept.getFirst(), where, problems);
        }
        if (type == null || !type.isString()) {
            problems.add(where + ": needs exactly one type");
            return null;
        }
        return checkType(type.asString(), where, problems);
    }

    private static String checkType(String type, String where, List<String> problems) {
        if (!TYPES.contains(type)) {
            problems.add(where + ": type " + type + " is not supported");
            return null;
        }
        return type;
    }

    private void copyText(ObjectNode from, ObjectNode to, String key) {
        String text = from.path(key).asString(null);
        if (text != null && !text.isBlank()) {
            to.put(key, text);
        }
    }

    /** {@code enum} and {@code const} fix the value, so they need no further limit; both are kept as the foreign schema has them. */
    private static void copyFixed(ObjectNode from, ObjectNode to) {
        if (from.path("enum").isArray() && !from.get("enum").isEmpty()) {
            to.set("enum", from.get("enum"));
        }
        if (from.has("const")) {
            to.set("const", from.get("const"));
        }
    }

    private ObjectNode string(ObjectNode flat, ObjectNode out, String path) {
        boolean fixed = out.has("enum") || out.has("const");
        long maxLength = fixed ? -1 : limitFor(flat.get("maxLength"), property(path));
        if (maxLength >= 0) {
            out.put("maxLength", maxLength);
        }
        JsonNode min = flat.get("minLength");
        if (min != null && min.isIntegralNumber() && min.asLong() > 0 && (maxLength < 0 || min.asLong() <= maxLength)) {
            out.put("minLength", min.asLong());
        }
        String pattern = flat.path("pattern").asString(null);
        if (pattern != null && anchored(pattern) && compiles(pattern)) {
            out.put("pattern", pattern);
        }
        return out;
    }

    private long limitFor(JsonNode declared, String property) {
        long configured = limits.stringMaxLengthByProperty().getOrDefault(property, limits.stringMaxLength());
        if (declared != null && declared.isIntegralNumber() && declared.asLong() >= 0) {
            return Math.min(declared.asLong(), configured);
        }
        return configured;
    }

    private static String property(String path) {
        int dot = path.lastIndexOf('.');
        String last = dot < 0 ? path : path.substring(dot + 1);
        return last.endsWith("[]") ? last.substring(0, last.length() - 2) : last;
    }

    private ObjectNode number(ObjectNode flat, ObjectNode out, String type) {
        if (out.has("enum") || out.has("const")) {
            return out;
        }
        boolean integer = "integer".equals(type);
        double minimum = bound(flat.get("minimum"), limits.numberMinimum(), true);
        double maximum = bound(flat.get("maximum"), limits.numberMaximum(), false);
        if (minimum > maximum) {
            minimum = limits.numberMinimum();
            maximum = limits.numberMaximum();
        }
        if (integer) {
            out.put("minimum", (long) minimum);
            out.put("maximum", (long) maximum);
        } else {
            out.put("minimum", minimum);
            out.put("maximum", maximum);
        }
        return out;
    }

    private static double bound(JsonNode declared, long configured, boolean lower) {
        if (declared != null && declared.isNumber()) {
            double value = declared.asDouble();
            return lower ? Math.max(value, configured) : Math.min(value, configured);
        }
        return configured;
    }

    private ObjectNode array(ObjectNode flat, ObjectNode out, JsonNode root, String path, int depth, int refDepth,
                             List<String> problems) {
        JsonNode items = flat.get("items");
        if (items == null) {
            problems.add(describe(path) + ": a list needs an items schema");
            return null;
        }
        ObjectNode element = visit(items, root, path + "[]", depth + 1, refDepth, problems);
        if (element == null) {
            return null;
        }
        JsonNode declared = flat.get("maxItems");
        long maxItems = limits.arrayMaxItems();
        if (declared != null && declared.isIntegralNumber() && declared.asLong() >= 0) {
            maxItems = Math.min(maxItems, declared.asLong());
        }
        out.put("maxItems", maxItems);
        JsonNode min = flat.get("minItems");
        if (min != null && min.isIntegralNumber() && min.asLong() > 0 && min.asLong() <= maxItems) {
            out.put("minItems", min.asLong());
        }
        if (flat.path("uniqueItems").asBoolean(false)) {
            out.put("uniqueItems", true);
        }
        out.set("items", element);
        return out;
    }

    private ObjectNode object(ObjectNode flat, ObjectNode out, JsonNode root, String path, int depth, int refDepth,
                              List<String> problems) {
        JsonNode additional = flat.get("additionalProperties");
        JsonNode properties = flat.path("properties");
        if (!properties.isObject() || properties.isEmpty()) {
            if (path.isEmpty()) {
                out.putObject("properties");
                out.put("additionalProperties", false);
                return out;
            }
            problems.add(describe(path) + ": an object without declared properties is a map and cannot be bounded");
            return null;
        }
        if (additional != null && !(additional.isBoolean() && !additional.asBoolean())) {
            problems.add(describe(path) + ": accepts undeclared properties");
            return null;
        }
        ObjectNode declared = out.putObject("properties");
        boolean ok = true;
        for (String name : properties.propertyNames()) {
            ObjectNode property = visit(properties.get(name), root, path.isEmpty() ? name : path + "." + name, depth + 1, refDepth, problems);
            if (property == null) {
                ok = false;
            } else {
                declared.set(name, property);
            }
        }
        if (!ok) {
            return null;
        }
        Set<String> required = new LinkedHashSet<>();
        if (flat.path("required").isArray()) {
            flat.get("required").forEach(r -> {
                if (declared.has(r.asString(""))) {
                    required.add(r.asString(""));
                }
            });
        }
        if (!required.isEmpty()) {
            ArrayNode list = out.putArray("required");
            required.forEach(list::add);
        }
        out.put("additionalProperties", false);
        return out;
    }

    private static String describe(String path) {
        return path.isEmpty() ? "the arguments" : path;
    }

    /** {@code ^...$} with the last {@code $} not escaped and no alternation outside a group or a class, as the starter requires. */
    static boolean anchored(String pattern) {
        if (!pattern.startsWith("^") || !pattern.endsWith("$")) {
            return false;
        }
        int backslashes = 0;
        for (int i = pattern.length() - 2; i >= 0 && pattern.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        if (backslashes % 2 == 1) {
            return false;
        }
        int depth = 0;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
            } else if (inClass) {
                inClass = c != ']';
            } else if (c == '[') {
                inClass = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '|' && depth == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean compiles(String pattern) {
        try {
            Pattern.compile(pattern);
            return true;
        } catch (PatternSyntaxException ex) {
            return false;
        }
    }
}
