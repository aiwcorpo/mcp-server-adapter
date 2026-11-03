package pl.aiwcorpo.mcp.adapter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A foreign MCP server for tests: streamable HTTP, answers as JSON or as an event stream, refuses a request without an
 * Authorization header the way mcp-atlassian does, and records what it was asked. Stands in for the real server so that no test needs
 * a container; one separate manual check ran the real image (README, "Demo").
 */
public final class StubForeignServer implements AutoCloseable {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    /** What the stub saw in a {@code tools/call}. */
    public record Call(String tool, JsonNode arguments, String authorization, String requestId, String sessionId) {
    }

    /** What the stub answers to a {@code tools/call}: an HTTP status, and for 200 the JSON-RPC {@code result} or {@code error}. */
    public record Reply(int status, String body) {

        public static Reply text(String text) {
            ObjectNode result = JSON.createObjectNode();
            result.putArray("content").addObject().put("type", "text").put("text", text);
            return result(result);
        }

        public static Reply result(ObjectNode result) {
            return new Reply(200, result.toString());
        }

        public static Reply errorResult(String text) {
            ObjectNode result = JSON.createObjectNode();
            result.putArray("content").addObject().put("type", "text").put("text", text);
            result.put("isError", true);
            return result(result);
        }

        public static Reply rpcError(int code) {
            return new Reply(200, "{\"__rpcError\":" + code + "}");
        }

        public static Reply status(int status) {
            return new Reply(status, "");
        }
    }

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> deletes = new CopyOnWriteArrayList<>();
    private final AtomicInteger sessions = new AtomicInteger();
    private volatile List<ObjectNode> tools = List.of();
    private volatile boolean eventStream = true;
    private volatile boolean requireAuthorization = true;
    private volatile Function<Call, Reply> handler = call -> Reply.text("ok");
    private volatile String redirectTo;

    public StubForeignServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/mcp", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubForeignServer tools(List<ObjectNode> tools) {
        this.tools = tools;
        return this;
    }

    public StubForeignServer eventStream(boolean value) {
        this.eventStream = value;
        return this;
    }

    public StubForeignServer requireAuthorization(boolean value) {
        this.requireAuthorization = value;
        return this;
    }

    public StubForeignServer onCall(Function<Call, Reply> value) {
        this.handler = value;
        return this;
    }

    public StubForeignServer redirectCallsTo(String location) {
        this.redirectTo = location;
        return this;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The Authorization header of every request, the discovery ones included. */
    public List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    public List<String> deletedSessions() {
        return List.copyOf(deletes);
    }

    public void reset() {
        calls.clear();
        authorizations.clear();
        deletes.clear();
        handler = call -> Reply.text("ok");
        redirectTo = null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(String.valueOf(authorization));
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            byte[] raw = exchange.getRequestBody().readAllBytes();
            if (requireAuthorization && authorization == null) {
                send(exchange, 401, "application/json", "{\"error\": \"Authentication required\"}");
                return;
            }
            JsonNode request = JSON.readTree(new String(raw, StandardCharsets.UTF_8));
            String method = request.path("method").asString("");
            switch (method) {
                case "initialize" -> {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "stub-session-" + sessions.incrementAndGet());
                    ObjectNode result = JSON.createObjectNode();
                    result.put("protocolVersion", "2025-06-18");
                    result.putObject("capabilities").putObject("tools");
                    result.putObject("serverInfo").put("name", "stub").put("version", "1");
                    respond(exchange, request, result.toString());
                }
                case "notifications/initialized" -> exchange.sendResponseHeaders(202, -1);
                case "tools/list" -> {
                    ObjectNode result = JSON.createObjectNode();
                    result.putArray("tools").addAll(tools);
                    respond(exchange, request, result.toString());
                }
                case "tools/call" -> call(exchange, request, authorization);
                default -> send(exchange, 400, "application/json", "{}");
            }
        }
    }

    private void call(HttpExchange exchange, JsonNode request, String authorization) throws IOException {
        if (redirectTo != null) {
            exchange.getResponseHeaders().add("Location", redirectTo);
            exchange.sendResponseHeaders(307, -1);
            return;
        }
        Call call = new Call(request.path("params").path("name").asString(""), request.path("params").path("arguments"),
                authorization, exchange.getRequestHeaders().getFirst("X-Request-Id"),
                exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
        calls.add(call);
        Reply reply = handler.apply(call);
        if (reply.status() != 200) {
            send(exchange, reply.status(), "application/json", "{\"error\": \"stub failure\"}");
            return;
        }
        respond(exchange, request, reply.body());
    }

    private void respond(HttpExchange exchange, JsonNode request, String result) throws IOException {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", request.get("id"));
        JsonNode body = JSON.readTree(result);
        if (body.has("__rpcError")) {
            envelope.putObject("error").put("code", body.path("__rpcError").asInt()).put("message", "stub error");
        } else {
            envelope.set("result", body);
        }
        if (eventStream) {
            String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"level\":\"info\"}}";
            send(exchange, 200, "text/event-stream",
                    "event: message\ndata: " + notification + "\n\nevent: message\ndata: " + envelope + "\n\n");
        } else {
            send(exchange, 200, "application/json", envelope.toString());
        }
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
