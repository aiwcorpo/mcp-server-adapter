package pl.aiwcorpo.mcp.adapter.foreign;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignCallException.Kind;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignCallException.Reason;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A minimal client of a foreign MCP server over streamable HTTP. One session per operation: a call opens a session with the
 * Authorization header of the calling user, makes the request and closes it, so no session (and no credential the server
 * kept with it) is ever shared between two users. Redirects are not followed, because a redirect would carry the header to
 * another address.
 *
 * <p>The server answers with JSON or with an event stream. Each exchange has one deadline ({@code request-timeout}) that covers the
 * headers and the body: the request timeout of the HTTP client ends at the headers, so a server that opens an event stream and
 * keeps it open, or trickles bytes, would otherwise hold the thread for ever. A stream is read only up to the response to the request
 * and then closed. Nothing the server says is logged or copied into an exception.
 */
public class ForeignMcpClient {

    /**
     * The tree of one answer costs far more heap than its bytes (a 2 MB body of empty objects is about 60 MB), so the number of tokens is
     * bounded as well; a tools/list of mcp-atlassian takes about 100 tokens per tool.
     */
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxTokenCount(100_000).build()).build()).build();
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_PAGES = 20;
    /** What the server puts in these headers is sent back to it, so only visible ASCII of a sane length is accepted. */
    private static final Pattern SESSION_ID = Pattern.compile("^[\\x21-\\x7E]{1,256}$");
    private static final Pattern PROTOCOL = Pattern.compile("^[0-9A-Za-z.-]{1,32}$");
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "foreign-deadline");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpClient http;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final long maxResponseBytes;
    private final AtomicInteger ids = new AtomicInteger();

    public ForeignMcpClient(HttpClient http, AdapterProperties.Foreign foreign) {
        this.http = http;
        this.endpoint = endpoint(foreign);
        this.requestTimeout = foreign.requestTimeout();
        this.maxResponseBytes = foreign.maxResponseSize().toBytes();
    }

    /**
     * The address every request goes to. The path may not change scheme, host or port of the base address: the credential of the user is
     * sent there, and {@code //other-host/mcp} or {@code http://other-host/mcp} would send it elsewhere.
     */
    public static URI endpoint(AdapterProperties.Foreign foreign) {
        String path = foreign.mcpPath();
        if (path == null || !path.matches("^/(?!/)[A-Za-z0-9._~!$&'()*+,;=:@%/-]*$") || path.contains("..")) {
            throw new IllegalStateException("aiwcorpo.mcp.adapter.foreign.mcp-path must be an absolute path that starts with a single /");
        }
        URI base = foreign.url();
        URI resolved = base.resolve(path);
        if (!base.getScheme().equalsIgnoreCase(resolved.getScheme()) || !base.getHost().equalsIgnoreCase(resolved.getHost())
                || base.getPort() != resolved.getPort() || resolved.getUserInfo() != null) {
            throw new IllegalStateException("aiwcorpo.mcp.adapter.foreign.mcp-path leaves the address of the foreign server");
        }
        return resolved;
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
                    throw new ForeignCallException(Kind.UNAVAILABLE, Reason.MALFORMED);
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
            throw new ForeignCallException(Kind.UNAVAILABLE, Reason.MALFORMED);
        }
    }

    /**
     * Calls a tool as the user whose Authorization header is given.
     *
     * @param requestId the platform's request id, passed as {@code X-Request-Id} so a line in the foreign server's log can be tied to the audit event
     * @return the {@code result} of the call
     * @throws ForeignCallException {@code DENIED} when the server answers with an error or an error result, {@code UNAUTHORIZED} when it does not
     *         accept the credential at the transport, {@code UNAVAILABLE} when it cannot be reached or answers nonsense
     */
    public JsonNode callTool(String name, JsonNode arguments, String authorization, String requestId) {
        try (Session session = open(authorization, requestId)) {
            ObjectNode params = JSON.createObjectNode();
            params.put("name", name);
            params.set("arguments", arguments);
            JsonNode result = session.request("tools/call", params);
            if (result.path("isError").asBoolean(false)) {
                throw new ForeignCallException(Kind.DENIED, Reason.RESULT_ERROR);
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
            String version = result.path("protocolVersion").asString(PROTOCOL_VERSION);
            if (!PROTOCOL.matcher(version).matches()) {
                throw new ForeignCallException(Kind.UNAVAILABLE, Reason.MALFORMED);
            }
            session.protocolVersion = version;
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
            Exchange exchange = send(message.toString(), id);
            if (sessionId == null && "initialize".equals(method) && exchange.sessionId != null) {
                if (!SESSION_ID.matcher(exchange.sessionId).matches()) {
                    throw new ForeignCallException(Kind.UNAVAILABLE, Reason.MALFORMED);
                }
                sessionId = exchange.sessionId;
            }
            JsonNode response = exchange.response;
            if (response.has("error")) {
                throw new ForeignCallException(Kind.DENIED, Reason.JSONRPC_ERROR);
            }
            JsonNode result = response.get("result");
            if (result == null || !result.isObject()) {
                throw new ForeignCallException(Kind.UNAVAILABLE, Reason.MALFORMED);
            }
            return result;
        }

        void notifyInitialized() {
            ObjectNode message = JSON.createObjectNode();
            message.put("jsonrpc", "2.0");
            message.put("method", "notifications/initialized");
            send(message.toString(), -1);
        }

        private Exchange send(String body, int id) {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            decorate(request);
            return execute(request.build(), id);
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

    private record Exchange(String sessionId, JsonNode response) {
    }

    /** Sends the request and reads the answer within one deadline. {@code id} below zero is a notification: no body is expected. */
    private Exchange execute(HttpRequest request, int id) {
        long deadline = System.nanoTime() + requestTimeout.toNanos();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream stream = response.body();
            AtomicBoolean expired = new AtomicBoolean();
            ScheduledFuture<?> watchdog = DEADLINES.schedule(() -> {
                expired.set(true);
                closeQuietly(stream);
            }, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try (InputStream body = new LimitedInputStream(stream, maxResponseBytes)) {
                int status = response.statusCode();
                refuseByStatus(status);
                String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
                if (id < 0) {
                    return new Exchange(sessionId, null);
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                JsonNode answer = contentType.contains("text/event-stream") ? fromEvents(body, id) : fromJson(body, id);
                return new Exchange(sessionId, answer);
            } catch (IOException ex) {
                throw new ForeignCallException(Kind.UNAVAILABLE, expired.get() ? Reason.TIMEOUT : Reason.UNREACHABLE);
            } finally {
                watchdog.cancel(false);
            }
        } catch (HttpTimeoutException ex) {
            throw new ForeignCallException(Kind.UNAVAILABLE, Reason.TIMEOUT);
        } catch (IOException ex) {
            throw new ForeignCallException(Kind.UNAVAILABLE, Reason.UNREACHABLE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ForeignCallException(Kind.UNAVAILABLE, Reason.UNREACHABLE);
        }
    }

    private static void refuseByStatus(int status) {
        if (status == 401) {
            throw new ForeignCallException(Kind.UNAUTHORIZED, Reason.HTTP_401, status);
        }
        if (status == 403) {
            throw new ForeignCallException(Kind.DENIED, Reason.HTTP_403, status);
        }
        if (status == 404) {
            throw new ForeignCallException(Kind.DENIED, Reason.HTTP_404, status);
        }
        if (status >= 300 && status < 400) {
            throw new ForeignCallException(Kind.UNAVAILABLE, Reason.HTTP_REDIRECT, status);
        }
        if (status >= 400 && status < 500) {
            throw new ForeignCallException(Kind.UNAVAILABLE, Reason.HTTP_OTHER_4XX, status);
        }
        if (status >= 500) {
            throw new ForeignCallException(Kind.UNAVAILABLE, Reason.HTTP_5XX, status);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ex) {
            // Closing is how the deadline ends a read that does not come back; a failure to close changes nothing.
        }
    }

    private static JsonNode fromJson(InputStream body, int id) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.transferTo(out);
        String text = out.toString(StandardCharsets.UTF_8);
        if (text.isBlank()) {
            throw new ForeignCallException(Kind.UNAVAILABLE, Reason.MALFORMED);
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (node.isArray()) {
                for (JsonNode element : node) {
                    if (isResponseTo(element, id)) {
                        return element;
                    }
                }
                throw new ForeignCallException(Kind.UNAVAILABLE, Reason.MALFORMED);
            }
            if (!isResponseTo(node, id)) {
                throw new ForeignCallException(Kind.UNAVAILABLE, Reason.MALFORMED);
            }
            return node;
        } catch (JacksonException ex) {
            throw new ForeignCallException(Kind.UNAVAILABLE, Reason.MALFORMED);
        }
    }

    /**
     * Events are separated by a blank line; the {@code data} lines of one event are joined with a newline. Reading stops at the response to
     * the request, so a server that keeps the stream open afterwards costs nothing.
     */
    private static JsonNode fromEvents(InputStream body, int id) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                JsonNode node = parseEvent(data, id);
                if (node != null) {
                    return node;
                }
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        JsonNode last = parseEvent(data, id);
        if (last == null) {
            throw new ForeignCallException(Kind.UNAVAILABLE, Reason.MALFORMED);
        }
        return last;
    }

    private static JsonNode parseEvent(StringBuilder data, int id) {
        if (data.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(data.toString());
            return isResponseTo(node, id) ? node : null;
        } catch (JacksonException ex) {
            // An event that is not JSON is not the response; the next one may be.
            return null;
        }
    }

    private static boolean isResponseTo(JsonNode node, int id) {
        return node.isObject() && node.path("id").isIntegralNumber() && node.path("id").asInt() == id
                && (node.has("result") || node.has("error"));
    }

    /** Fails the read that goes past the limit; the check is on bytes read, so a slow trickle and a flood are both bounded. */
    private static final class LimitedInputStream extends FilterInputStream {

        private final long limit;
        private long total;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) {
            total += bytes;
            if (total > limit) {
                throw new ForeignCallException(Kind.OVERSIZE, Reason.OVERSIZE);
            }
        }
    }
}
