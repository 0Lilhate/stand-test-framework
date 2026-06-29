package ru.alfa.stand.test.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny local HTTP server (JDK {@link HttpServer}) that records the received request and replies with
 * a configurable status and body. Used to exercise {@link WebClientHttpCaller} end-to-end without any
 * external dependency.
 */
final class RecordingHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, List<String>> capturedHeaders = new ConcurrentHashMap<>();
    private volatile String capturedMethod;
    private volatile String capturedPath;
    private volatile String capturedRawQuery;
    private volatile String capturedBody;
    private int responseStatus = 200;
    private String responseBody = "{}";

    RecordingHttpServer() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException("could not start test server", failure);
        }
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    RecordingHttpServer respond(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    String capturedMethod() {
        return this.capturedMethod;
    }

    String capturedPath() {
        return this.capturedPath;
    }

    String capturedRawQuery() {
        return this.capturedRawQuery;
    }

    String capturedBody() {
        return this.capturedBody;
    }

    String capturedHeader(String name) {
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
            byte[] payload = this.responseBody.getBytes(StandardCharsets.UTF_8);
            if (payload.length == 0) {
                exchange.sendResponseHeaders(this.responseStatus, -1);
            } else {
                exchange.sendResponseHeaders(this.responseStatus, payload.length);
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
