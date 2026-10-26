package dev.astra;

import dev.astra.client.SimpleClient;
import dev.astra.server.TcpServer;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Astra lesson 03: server [port] | client [port]");
        } else if (args[0].equals("server")) {
            try (var server = new TcpServer("127.0.0.1", args.length > 1 ? Integer.parseInt(args[1]) : 9000)) {
                System.out.println("Listening on " + server.port() + "; press Enter to stop.");
                System.in.read();
            }
        } else if (args[0].equals("client")) {
            System.out.println("Acknowledged bytes: " + SimpleClient.upload("127.0.0.1",
                    args.length > 1 ? Integer.parseInt(args[1]) : 9000, 1L << 20, 10));
        } else throw new IllegalArgumentException("Expected server or client");
    }
}
