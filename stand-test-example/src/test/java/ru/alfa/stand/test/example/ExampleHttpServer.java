package ru.alfa.stand.test.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny local HTTP server (JDK {@link HttpServer}) double for the REST examples: it records the request
 * it received and replies with a configured status and body, so the examples exercise real HTTP without
 * an external stand (plan §16). Listens on a loopback port — ephemeral by default (manual-runner
 * examples), or a fixed one for the ServiceLoader-wired {@code @StandTest} path where the env-ref
 * {@code CLIENT_SERVICE_URL} pins the address.
 */
final class ExampleHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, List<String>> headers = new ConcurrentHashMap<>();
    private final int status;
    private final List<String> responseBodies;
    private final AtomicInteger served = new AtomicInteger();
    private volatile String requestBody = "";

    ExampleHttpServer(int status, String responseBody) {
        this(status, List.of(responseBody), 0);
    }

    ExampleHttpServer(int status, String responseBody, int port) {
        this(status, List.of(responseBody), port);
    }

    /**
     * Serves the bodies in order, one per request, repeating the last one — a converging stand for
     * the {@code rest.expectEventually} example.
     */
    ExampleHttpServer(int status, List<String> responseBodies, int port) {
        if (responseBodies.isEmpty()) {
            throw new IllegalArgumentException("at least one response body is required");
        }
        this.status = status;
        this.responseBodies = List.copyOf(responseBodies);
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException failure) {
            throw new IllegalStateException("could not start the example HTTP server", failure);
        }
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    String receivedBody() {
        return this.requestBody;
    }

    String receivedHeader(String name) {
        for (Map.Entry<String, List<String>> entry : this.headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            this.headers.putAll(exchange.getRequestHeaders());
            this.requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int index = Math.min(this.served.getAndIncrement(), this.responseBodies.size() - 1);
            byte[] payload = this.responseBodies.get(index).getBytes(StandardCharsets.UTF_8);
            if (payload.length == 0) {
                exchange.sendResponseHeaders(this.status, -1);
            } else {
                exchange.sendResponseHeaders(this.status, payload.length);
                exchange.getResponseBody().write(payload);
            }
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}
