package net.defade.rhenium.rest;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.net.InetSocketAddress;

public class RestServer extends Authenticator {
    public static final String AUTH_KEY = System.getenv("REST_AUTH_KEY");
    private static final Logger LOGGER = LogManager.getLogger(RestServer.class);

    private final String host;
    private final int port;
    private final HttpServer httpServer;

    public RestServer(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
    }

    public void start() {
        httpServer.start();
        LOGGER.info("REST server started on {}:{}", host, port);
    }

    public void stop() {
        httpServer.stop(0);
        LOGGER.info("REST server stopped");
    }

    public void registerEndpoint(String path, HttpHandler endpoint) {
        HttpContext context = httpServer.createContext(path, endpoint);
        context.setAuthenticator(this);

        LOGGER.info("Registered endpoint at {}", "http://" + httpServer.getAddress().getHostString() + ":" + httpServer.getAddress().getPort() + path);
    }

    @Override
    public Result authenticate(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.equals(AUTH_KEY)) {
            return new Failure(401);
        }

        return new Success(new HttpPrincipal("admin", "admin"));
    }
}
