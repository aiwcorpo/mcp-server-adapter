package pl.aiwcorpo.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.adapter.schema.SchemaTightener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class SchemaTightenerTest {

    private final SchemaTightener tightener = new SchemaTightener(Fixtures.limits());

    private SchemaTightener.Result tighten(String schema) {
        return tightener.tighten(Fixtures.tree(schema));
    }

    @Test
    void everyStringListAndNumberGetsALimitAndEveryObjectIsClosed() {
        SchemaTightener.Result result = tighten("""
                {"type":"object","properties":{
                  "text":{"type":"string"},
                  "tags":{"type":"array","items":{"type":"string"}},
                  "count":{"type":"integer"},
                  "ratio":{"type":"number","minimum":0},
                  "flag":{"type":"boolean"},
                  "nested":{"type":"object","properties":{"id":{"type":"string","maxLength":10}}}},
                 "required":["text"]}""");

        ObjectNode schema = result.schema().orElseThrow();
        assertThat(schema.at("/additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.at("/properties/text/maxLength").asInt()).isEqualTo(4096);
        assertThat(schema.at("/properties/tags/maxItems").asInt()).isEqualTo(100);
        assertThat(schema.at("/properties/tags/items/maxLength").asInt()).isEqualTo(4096);
        assertThat(schema.at("/properties/count/minimum").asLong()).isEqualTo(-2147483648L);
        assertThat(schema.at("/properties/count/maximum").asLong()).isEqualTo(2147483647L);
        assertThat(schema.at("/properties/ratio/minimum").asDouble()).isEqualTo(0.0);
        assertThat(schema.at("/properties/nested/additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.at("/properties/nested/properties/id/maxLength").asInt()).isEqualTo(10);
        assertThat(schema.at("/required/0").asString()).isEqualTo("text");
    }

    @Test
    void aDeclaredLimitStaysWhenItIsTighterAndTheConfiguredOneWinsWhenItIsLooser() {
        ObjectNode schema = tighten("""
                {"type":"object","properties":{"a":{"type":"string","maxLength":10},"b":{"type":"string","maxLength":999999}}}""")
                .schema().orElseThrow();

        assertThat(schema.at("/properties/a/maxLength").asInt()).isEqualTo(10);
        assertThat(schema.at("/properties/b/maxLength").asInt()).isEqualTo(4096);
    }

    @Test
    void aLimitByPropertyNameOverridesTheDefault() {
        ObjectNode schema = tighten("""
                {"type":"object","properties":{"body":{"type":"string"},"jql":{"type":"string","maxLength":99999}}}""")
                .schema().orElseThrow();

        assertThat(schema.at("/properties/body/maxLength").asInt()).isEqualTo(32768);
        assertThat(schema.at("/properties/jql/maxLength").asInt()).isEqualTo(2048);
    }

    @Test
    void aNullableAlternativeBecomesTheTypeAndTheDefaultIsDropped() {
        ObjectNode schema = tighten("""
                {"type":"object","properties":{"expand":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null,"description":"Fields to expand"},
                 "kind":{"type":["string","null"]}}}""").schema().orElseThrow();

        assertThat(schema.at("/properties/expand/type").asString()).isEqualTo("string");
        assertThat(schema.at("/properties/expand/description").asString()).isEqualTo("Fields to expand");
        assertThat(schema.at("/properties/expand").has("default")).isFalse();
        assertThat(schema.at("/properties/expand").has("anyOf")).isFalse();
        assertThat(schema.at("/properties/kind/type").asString()).isEqualTo("string");
    }

    @Test
    void aLocalReferenceIsWrittenOut() {
        ObjectNode schema = tighten("""
                {"type":"object","$defs":{"Key":{"type":"string","pattern":"^[A-Z]+-[0-9]+$","maxLength":20}},
                 "properties":{"key":{"$ref":"#/$defs/Key"},"keys":{"type":"array","items":{"$ref":"#/$defs/Key"}}}}""")
                .schema().orElseThrow();

        assertThat(schema.at("/properties/key/pattern").asString()).isEqualTo("^[A-Z]+-[0-9]+$");
        assertThat(schema.at("/properties/keys/items/maxLength").asInt()).isEqualTo(20);
        assertThat(schema.toString()).doesNotContain("$ref", "$defs");
    }

    @Test
    void anOpenMapIsRefused() {
        SchemaTightener.Result result = tighten("""
                {"type":"object","properties":{"fields":{"type":"object","additionalProperties":true}}}""");

        assertThat(result.schema()).isEmpty();
        assertThat(result.problems()).anyMatch(problem -> problem.startsWith("fields:") && problem.contains("map"));
    }

    @Test
    void aUnionOfTwoTypesIsRefused() {
        assertThat(tighten("""
                {"type":"object","properties":{"value":{"anyOf":[{"type":"string"},{"type":"integer"}]}}}""").schema()).isEmpty();
        assertThat(tighten("""
                {"type":"object","properties":{"value":{"type":["string","integer"]}}}""").schema()).isEmpty();
    }

    @Test
    void aRecursiveReferenceAndARemoteReferenceAreRefused() {
        assertThat(tighten("""
                {"type":"object","$defs":{"Node":{"$ref":"#/$defs/Node"}},"properties":{"n":{"$ref":"#/$defs/Node"}}}""").schema())
                .isEmpty();
        assertThat(tighten("""
                {"type":"object","properties":{"n":{"$ref":"https://elsewhere.test/schema.json"}}}""").schema()).isEmpty();
    }

    @Test
    void aListWithoutItemsAndATooDeepSchemaAreRefused() {
        assertThat(tighten("{\"type\":\"object\",\"properties\":{\"l\":{\"type\":\"array\"}}}").schema()).isEmpty();
        String deep = "{\"type\":\"string\"}";
        for (int i = 0; i < 10; i++) {
            deep = "{\"type\":\"object\",\"properties\":{\"x\":" + deep + "}}";
        }
        assertThat(tighten(deep).schema()).isEmpty();
    }

    @Test
    void anUnanchoredOrBrokenPatternIsDroppedNotKept() {
        ObjectNode schema = tighten("""
                {"type":"object","properties":{"a":{"type":"string","pattern":"abc"},"b":{"type":"string","pattern":"^a|b$"},
                 "c":{"type":"string","pattern":"^(unclosed$"},"d":{"type":"string","pattern":"^[a-z]+$"}}}""")
                .schema().orElseThrow();

        assertThat(schema.at("/properties/a").has("pattern")).isFalse();
        assertThat(schema.at("/properties/b").has("pattern")).isFalse();
        assertThat(schema.at("/properties/c").has("pattern")).isFalse();
        assertThat(schema.at("/properties/d/pattern").asString()).isEqualTo("^[a-z]+$");
    }

    @Test
    void anEnumNeedsNoLength() {
        ObjectNode schema = tighten("""
                {"type":"object","properties":{"kind":{"type":"string","enum":["a","b"]}}}""").schema().orElseThrow();

        assertThat(schema.at("/properties/kind").has("maxLength")).isFalse();
        assertThat(schema.at("/properties/kind/enum")).hasSize(2);
    }

    @Test
    void keywordsNobodyReviewedAreNotPassedOn() {
        ObjectNode schema = tighten("""
                {"type":"object","$schema":"x","title":"T","x-vendor":{"a":1},"properties":{
                 "s":{"type":"string","format":"uri","examples":["a"],"contentEncoding":"base64","title":"S"}}}""")
                .schema().orElseThrow();

        assertThat(schema.has("x-vendor")).isFalse();
        assertThat(schema.has("$schema")).isFalse();
        assertThat(schema.at("/properties/s").propertyNames()).containsExactlyInAnyOrder("type", "maxLength");
    }

    @Test
    void theRealSchemasOfMcpAtlassianAreBounded() {
        for (ObjectNode tool : Fixtures.atlassianTools()) {
            SchemaTightener.Result result = tightener.tighten(tool.get("inputSchema"));

            assertThat(result.schema()).as(tool.get("name").asString()).isPresent();
            assertBounded(result.schema().get(), tool.get("name").asString());
        }
    }

    private static void assertBounded(JsonNode schema, String where) {
        String type = schema.path("type").asString();
        assertThat(type).as(where).isIn("string", "integer", "number", "boolean", "array", "object");
        for (String keyword : new String[] {"anyOf", "oneOf", "allOf", "not", "$ref", "default"}) {
            assertThat(schema.has(keyword)).as(where + " has " + keyword).isFalse();
        }
        switch (type) {
            case "object" -> {
                assertThat(schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean())
                        .as(where + " closed").isTrue();
                schema.path("properties").propertyNames().forEach(name -> assertBounded(schema.path("properties").get(name), where + "." + name));
            }
            case "array" -> {
                assertThat(schema.path("maxItems").isIntegralNumber()).as(where + " maxItems").isTrue();
                assertBounded(schema.get("items"), where + "[]");
            }
            case "string" -> assertThat(schema.has("maxLength") || schema.has("enum")).as(where + " maxLength").isTrue();
            case "integer", "number" -> assertThat(schema.has("minimum") && schema.has("maximum")).as(where + " range").isTrue();
            default -> { }
        }
    }
}
