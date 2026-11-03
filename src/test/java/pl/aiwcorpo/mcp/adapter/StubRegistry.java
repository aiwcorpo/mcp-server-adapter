package pl.aiwcorpo.mcp.adapter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The registry side of {@code submitAdapterManifest} for tests (plain http, no client certificate: the certificate is the
 * business of the TLS bundle and is exercised in the stack, not here). Answers with the statuses it is told to, in order, then 200.
 */
public final class StubRegistry implements AutoCloseable {

    public record Put(String path, String contentType, String body) {
    }

    private final HttpServer server;
    private final List<Put> puts = new CopyOnWriteArrayList<>();
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();
    private final AtomicInteger index = new AtomicInteger();

    public StubRegistry() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.createContext("/admin/v1/registry/adapters/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Statuses for the next requests, in order; after them every request gets 200. */
    public StubRegistry answer(Integer... values) {
        statuses.clear();
        statuses.addAll(List.of(values));
        index.set(0);
        puts.clear();
        return this;
    }

    public List<Put> puts() {
        return List.copyOf(puts);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("PUT".equals(exchange.getRequestMethod())) {
                puts.add(new Put(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Content-Type"), body));
            }
            int position = index.getAndIncrement();
            int status = position < statuses.size() ? statuses.get(position) : 200;
            String reason = status == 422 ? "adapter_mode_mismatch" : status == 403 ? "client_certificate_rejected" : null;
            byte[] reply = (status == 200 ? "{}" : "{\"error\":\"rejected\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                    + ",\"message\":\"secret detail\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
