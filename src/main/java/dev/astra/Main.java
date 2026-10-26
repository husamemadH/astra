package dev.astra;

import dev.astra.client.SimpleClient;
import dev.astra.server.TcpServer;
import dev.astra.server.ServerConfig;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Astra lesson 04: server [port] [baseline|thread-per-connection|pool] | client [port]");
        } else if (args[0].equals("server")) {
            var config = new ServerConfig();
            config.port = args.length > 1 ? Integer.parseInt(args[1]) : 9000;
            config.design = args.length > 2 ? ServerConfig.Design.valueOf(args[2].toUpperCase().replace('-', '_')) : ServerConfig.Design.BASELINE;
            try (var server = new TcpServer(config)) {
                System.out.println("Listening on " + server.port() + "; press Enter to stop.");
                System.in.read();
            }
        } else if (args[0].equals("client")) {
            System.out.println("Acknowledged bytes: " + SimpleClient.upload("127.0.0.1",
                    args.length > 1 ? Integer.parseInt(args[1]) : 9000, 1L << 20, 10));
        } else throw new IllegalArgumentException("Expected server or client");
    }
}
