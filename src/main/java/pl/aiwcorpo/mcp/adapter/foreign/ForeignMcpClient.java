package pl.aiwcorpo.mcp.adapter.foreign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignCallException.Kind;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A minimal client of a foreign MCP server over streamable HTTP. One session per operation: a call opens a session with the
 * Authorization header of the calling user, makes the request and closes it, so no session (and no credential the server
 * kept with it) is ever shared between two users. Redirects are not followed, because a redirect would carry the header to
 * another address.
 *
 * <p>The server answers with JSON or with an event stream; both are read, up to {@code max-response-size}. Nothing the server
 * says is logged or copied into an exception.
 */
public class ForeignMcpClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_PAGES = 20;

    private final HttpClient http;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final long maxResponseBytes;
    private final AtomicInteger ids = new AtomicInteger();

    public ForeignMcpClient(HttpClient http, AdapterProperties.Foreign foreign) {
        this.http = http;
        this.endpoint = foreign.url().resolve(foreign.mcpPath());
        this.requestTimeout = foreign.requestTimeout();
        this.maxResponseBytes = foreign.maxResponseSize().toBytes();
    }

    /** Builds the HTTP client: no redirects, HTTP/1.1 (the foreign servers are ordinary ASGI or servlet applications), connect timeout. */
    public static HttpClient httpClient(AdapterProperties.Foreign foreign, javax.net.ssl.SSLContext tls) {
        HttpClient.Builder builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(foreign.connectTimeout());
        if (tls != null) {
            builder.sslContext(tls);
            javax.net.ssl.SSLParameters parameters = new javax.net.ssl.SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            builder.sslParameters(parameters);
        }
        return builder.build();
    }

    /** The tools the server lists for the given Authorization header (all pages). */
    public List<ObjectNode> listTools(String authorization) {
        try (Session session = open(authorization, null)) {
            List<ObjectNode> tools = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                ObjectNode params = JSON.createObjectNode();
                if (cursor != null) {
                    params.put("cursor", cursor);
                }
                JsonNode result = session.request("tools/list", params);
                JsonNode list = result.path("tools");
                if (!list.isArray()) {
                    throw new ForeignCallException(Kind.UNAVAILABLE, "tools/list without a tools array");
                }
                list.forEach(tool -> {
                    if (tool.isObject()) {
                        tools.add((ObjectNode) tool);
                    }
                });
                cursor = result.path("nextCursor").asString(null);
                if (cursor == null || cursor.isEmpty()) {
                    return tools;
                }
            }
            throw new ForeignCallException(Kind.UNAVAILABLE, "tools/list has more than " + MAX_PAGES + " pages");
        }
    }

    /**
     * Calls a tool as the user whose Authorization header is given.
     *
     * @param requestId the platform's request id, passed as {@code X-Request-Id} so a line in the foreign server's log can be tied to the audit event
     * @return the {@code result} of the call
     * @throws ForeignCallException {@code DENIED} when the server answers with an error or an error result, {@code UNAVAILABLE} when it cannot be reached or answers nonsense
     */
    public JsonNode callTool(String name, JsonNode arguments, String authorization, String requestId) {
        try (Session session = open(authorization, requestId)) {
            ObjectNode params = JSON.createObjectNode();
            params.put("name", name);
            params.set("arguments", arguments);
            JsonNode result = session.request("tools/call", params);
            if (result.path("isError").asBoolean(false)) {
                throw new ForeignCallException(Kind.DENIED, "the tool reported an error");
            }
            return result;
        }
    }

    private Session open(String authorization, String requestId) {
        Session session = new Session(authorization, requestId);
        try {
            ObjectNode params = JSON.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.putObject("capabilities");
            ObjectNode info = params.putObject("clientInfo");
            info.put("name", "mcp-server-adapter");
            info.put("version", "1");
            JsonNode result = session.request("initialize", params);
            session.protocolVersion = result.path("protocolVersion").asString(PROTOCOL_VERSION);
            session.notifyInitialized();
            return session;
        } catch (RuntimeException ex) {
            session.close();
            throw ex;
        }
    }

    private final class Session implements AutoCloseable {

        private final String authorization;
        private final String requestId;
        private String sessionId;
        private String protocolVersion;

        Session(String authorization, String requestId) {
            this.authorization = authorization;
            this.requestId = requestId;
        }

        JsonNode request(String method, ObjectNode params) {
            int id = ids.incrementAndGet();
            ObjectNode message = JSON.createObjectNode();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.set("params", params);
            Exchange exchange = send(message.toString());
            if (sessionId == null && "initialize".equals(method)) {
                sessionId = exchange.sessionId;
            }
            JsonNode response = answer(exchange, id);
            if (response.has("error")) {
                throw new ForeignCallException(Kind.DENIED, "JSON-RPC error " + response.path("error").path("code").asInt(0));
            }
            JsonNode result = response.get("result");
            if (result == null || !result.isObject()) {
                throw new ForeignCallException(Kind.UNAVAILABLE, "response without a result");
            }
            return result;
        }

        void notifyInitialized() {
            ObjectNode message = JSON.createObjectNode();
            message.put("jsonrpc", "2.0");
            message.put("method", "notifications/initialized");
            send(message.toString());
        }

        private Exchange send(String body) {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            decorate(request);
            return execute(request.build());
        }

        private void decorate(HttpRequest.Builder request) {
            if (authorization != null && !authorization.isEmpty()) {
                request.header("Authorization", authorization);
            }
            if (requestId != null) {
                request.header("X-Request-Id", requestId);
            }
            if (sessionId != null) {
                request.header("Mcp-Session-Id", sessionId);
            }
            if (protocolVersion != null) {
                request.header("MCP-Protocol-Version", protocolVersion);
            }
        }

        @Override
        public void close() {
            if (sessionId == null) {
                return;
            }
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(requestTimeout).DELETE();
                decorate(request);
                http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException | RuntimeException ex) {
                // The session ends with the server's own timeout; nothing depends on the DELETE.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record Exchange(int status, String contentType, String sessionId, String body) {
    }

    private Exchange execute(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                int status = response.statusCode();
                if (status == 401 || status == 403 || status == 404) {
                    throw new ForeignCallException(Kind.DENIED, status, "HTTP " + status);
                }
                if (status >= 300 && status != 202) {
                    throw new ForeignCallException(Kind.UNAVAILABLE, status, "HTTP " + status);
                }
                String body = read(stream);
                return new Exchange(status, response.headers().firstValue("Content-Type").orElse(""),
                        response.headers().firstValue("Mcp-Session-Id").orElse(null), body);
            }
        } catch (IOException ex) {
            throw new ForeignCallException(Kind.UNAVAILABLE, "no answer: " + ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ForeignCallException(Kind.UNAVAILABLE, "interrupted");
        }
    }

    private String read(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = stream.read(buffer)) > 0) {
            total += count;
            if (total > maxResponseBytes) {
                throw new ForeignCallException(Kind.OVERSIZE, "response larger than " + maxResponseBytes + " bytes");
            }
            out.write(buffer, 0, count);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static JsonNode answer(Exchange exchange, int id) {
        if (exchange.body.isBlank()) {
            throw new ForeignCallException(Kind.UNAVAILABLE, "empty answer");
        }
        try {
            if (exchange.contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                return fromEvents(exchange.body, id).orElseThrow(
                        () -> new ForeignCallException(Kind.UNAVAILABLE, "no response for the request in the event stream"));
            }
            JsonNode node = JSON.readTree(exchange.body);
            if (node.isArray()) {
                for (JsonNode element : node) {
                    if (isResponseTo(element, id)) {
                        return element;
                    }
                }
                throw new ForeignCallException(Kind.UNAVAILABLE, "no response for the request in the batch");
            }
            if (!isResponseTo(node, id)) {
                throw new ForeignCallException(Kind.UNAVAILABLE, "the answer is not the response to the request");
            }
            return node;
        } catch (JacksonException ex) {
            throw new ForeignCallException(Kind.UNAVAILABLE, "the answer is not JSON");
        }
    }

    private static boolean isResponseTo(JsonNode node, int id) {
        return node.isObject() && node.path("id").isIntegralNumber() && node.path("id").asInt() == id
                && (node.has("result") || node.has("error"));
    }

    /** Events are separated by a blank line; the {@code data} lines of one event are joined with a newline. */
    private static Optional<JsonNode> fromEvents(String stream, int id) {
        StringBuilder data = new StringBuilder();
        for (String line : (stream + "\n\n").split("\r?\n", -1)) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    try {
                        JsonNode node = JSON.readTree(data.toString());
                        if (isResponseTo(node, id)) {
                            return Optional.of(node);
                        }
                    } catch (JacksonException ex) {
                        // An event that is not JSON is not the response; the next one may be.
                    }
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        return Optional.empty();
    }
}
