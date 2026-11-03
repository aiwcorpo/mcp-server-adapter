package pl.aiwcorpo.mcp.adapter.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * Turns the result of the foreign tool into the platform's shape for text that came from a system: an
 * {@code UntrustedText} (CC-072) with {@code kind: untrusted-data}, so a client sees data and not an instruction. Only text content
 * is passed on, normalized by {@code UntrustedText} and cut to {@code result-max-chars}; images, resources and structured content
 * are dropped (the text of a text-only tool repeats them), and the count of what was dropped is the one thing added next to the text.
 */
final class ForeignResult {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int maxChars;

    ForeignResult(int maxChars) {
        this.maxChars = maxChars;
    }

    String render(JsonNode result) {
        StringBuilder text = new StringBuilder();
        int dropped = 0;
        for (JsonNode content : result.path("content")) {
            if ("text".equals(content.path("type").asString("")) && content.path("text").isString()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(content.path("text").asString());
            } else {
                dropped++;
            }
        }
        String normalized = TextHygiene.normalize(text.toString());
        String cut = UntrustedText.cut(normalized, maxChars);
        UntrustedText untrusted = UntrustedText.plainCut(cut, cut.length() < normalized.length());
        ObjectNode out = JSON.valueToTree(untrusted);
        if (dropped > 0) {
            out.put("omittedContentItems", dropped);
        }
        return out.toString();
    }
}
