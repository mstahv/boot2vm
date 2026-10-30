package hello;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;

/** The smallest possible web app: answers every request with its version and a greeting from the environment. */
public class Main {
    static final String VERSION = "v1";

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            byte[] body = (VERSION + " " + System.getenv().getOrDefault("GREETING", "-")).getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
}
