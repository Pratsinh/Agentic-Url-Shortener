package com.ascs.agentic;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A local stand-in for the GitHub REST API: records every request and answers with a scripted response. */
public final class FakeGitHub implements AutoCloseable {

    public record Request(String method, String path, String authorization, String body) {}

    public record Response(int status, String body) {}

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    public FakeGitHub(Function<Request, Response> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            Request req = new Request(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath() + (query == null ? "" : "?" + query),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(req);
            Response res = handler.apply(req);
            byte[] bytes = res.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(res.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
