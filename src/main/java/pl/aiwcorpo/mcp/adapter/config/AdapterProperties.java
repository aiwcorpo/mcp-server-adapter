package pl.aiwcorpo.mcp.adapter.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the adapter that the starter does not know: where the foreign server is, how its schemas are bounded, where
 * which tools it exposes. Everything about the caller and the credential transport stays under
 * {@code aiwcorpo.mcp.platform} (starter).
 */
@ConfigurationProperties("aiwcorpo.mcp.adapter")
public record AdapterProperties(@DefaultValue Foreign foreign, @DefaultValue Limits limits, @DefaultValue Tools tools) {

    /**
     * @param url                    base URL of the foreign server, without the MCP path
     * @param mcpPath                path of its streamable HTTP endpoint
     * @param authorizationFormat    the Authorization header sent with a call; {@code {credential}} is replaced with the value of the
     *                               user's credential (C3), so a server that wants {@code Bearer} or {@code Token} is a setting
     * @param discoveryAuthorization the Authorization header of the startup {@code tools/list}; empty sends none. It is not a credential
     *                               of anyone: some servers refuse any request without the header, but the list needs no access to the system
     * @param allowPlainHttp         http instead of https outside the {@code dev} profile, for a sidecar and its server on one private network
     * @param sslBundle              Spring SSL bundle with the CA that signed the foreign server (https only)
     */
    public record Foreign(URI url, @DefaultValue("/mcp") String mcpPath,
                          @DefaultValue("Token {credential}") String authorizationFormat,
                          @DefaultValue("") String discoveryAuthorization,
                          @DefaultValue("2s") Duration connectTimeout, @DefaultValue("30s") Duration requestTimeout,
                          @DefaultValue("90s") Duration startupWait, @DefaultValue("2MB") DataSize maxResponseSize,
                          @DefaultValue("false") boolean allowPlainHttp, String sslBundle) {
    }

    /**
     * Bounds the adapter puts on the schemas of the foreign server (CC-078, CC-123): the contract wants every string, list and
     * number limited and the foreign server states none of them.
     *
     * @param stringMaxLengthByProperty the limit of a text argument by its name, where the default is too small ({@code body}) or too large
     * @param descriptionMaxLength      the foreign description is text that reaches the model, so it is cut
     * @param propertyDescriptionMaxLength the same for the description of one argument
     * @param enumValueMaxLength        longest text in an {@code enum} or {@code const}; longer or containing forbidden characters refuses the tool
     * @param schemaMaxNodes            most nodes of one written-out schema, against a schema that grows exponentially through shared references
     * @param resultMaxChars            the text of a result is cut to this length
     * @param callsInFlight             most foreign calls at once; one call can hold some tens of MB of heap (the answer, its text and its
     *                                  tree), so this times that stays well below the heap, and a call beyond it is refused as unavailable
     */
    public record Limits(@DefaultValue("4096") int stringMaxLength,
                         @DefaultValue Map<String, Integer> stringMaxLengthByProperty,
                         @DefaultValue("100") int arrayMaxItems, @DefaultValue("-2147483648") long numberMinimum,
                         @DefaultValue("2147483647") long numberMaximum, @DefaultValue("2000") int descriptionMaxLength,
                         @DefaultValue("1000") int propertyDescriptionMaxLength, @DefaultValue("256") int enumValueMaxLength,
                         @DefaultValue("2000") int schemaMaxNodes, @DefaultValue("100000") int resultMaxChars,
                         @DefaultValue("8") int callsInFlight) {
    }

    /**
     * @param include     names (as the foreign server lists them) the adapter exposes; empty exposes every tool it can bound.
     *                    A second line of defence behind the foreign server's own switch, not instead of it
     * @param stripPrefix a prefix removed from the foreign names ({@code jira_}), so the tools keep the names a server of that system has
     */
    public record Tools(@DefaultValue List<String> include, @DefaultValue("") String stripPrefix) {
    }
}
