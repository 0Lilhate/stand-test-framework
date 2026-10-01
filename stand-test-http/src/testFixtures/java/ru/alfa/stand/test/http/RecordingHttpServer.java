package ru.alfa.stand.test.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A tiny local HTTP server (JDK {@link HttpServer}) that records the received request and replies with
 * a configurable status and body. Used to exercise {@link WebClientHttpCaller} end-to-end without any
 * external dependency.
 */
public final class RecordingHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, List<String>> capturedHeaders = new ConcurrentHashMap<>();
    private final Deque<CannedResponse> responses = new ConcurrentLinkedDeque<>();
    private volatile String capturedMethod;
    private volatile String capturedPath;
    private volatile String capturedRawQuery;
    private volatile String capturedBody;
    private volatile CannedResponse lastResponse = new CannedResponse(200, "{}");

    public RecordingHttpServer() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException("could not start test server", failure);
        }
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    public RecordingHttpServer respond(int status, String body) {
        this.responses.clear();
        this.lastResponse = new CannedResponse(status, body);
        return this;
    }

    /**
     * Queues a sequence of responses for polling tests; the last one repeats once the queue drains.
     */
    public RecordingHttpServer respondSequence(int status, String body) {
        this.responses.addLast(new CannedResponse(status, body));
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    public String capturedMethod() {
        return this.capturedMethod;
    }

    public String capturedPath() {
        return this.capturedPath;
    }

    public String capturedRawQuery() {
        return this.capturedRawQuery;
    }

    public String capturedBody() {
        return this.capturedBody;
    }

    public String capturedHeader(String name) {
        for (Map.Entry<String, List<String>> entry : this.capturedHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            this.capturedMethod = exchange.getRequestMethod();
            this.capturedPath = exchange.getRequestURI().getPath();
            this.capturedRawQuery = exchange.getRequestURI().getRawQuery();
            this.capturedHeaders.putAll(exchange.getRequestHeaders());
            this.capturedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            CannedResponse queued = this.responses.pollFirst();
            if (queued != null) {
                this.lastResponse = queued;
            }
            CannedResponse response = this.lastResponse;
            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            if (payload.length == 0) {
                exchange.sendResponseHeaders(response.status(), -1);
            } else {
                exchange.sendResponseHeaders(response.status(), payload.length);
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

    private record CannedResponse(int status, String body) {
    }
}
