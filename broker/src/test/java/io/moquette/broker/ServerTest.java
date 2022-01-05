package io.moquette.broker;

import org.junit.jupiter.api.Test;

import java.io.IOException;

public class ServerTest {

    @Test
    public void startServer() throws IOException {
        System.setProperty("moquette.path", "/Users/icefox/Documents/github/icefoxs/moquette/broker/src/test/resources/");
        final Server server = new Server();
        server.startServer();
        System.out.println("Server started, version 0.16-SNAPSHOT");
        //Bind a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stopServer));
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("moquette.path", "/Users/icefox/Documents/github/icefoxs/moquette/broker/src/test/resources/");
        final Server server = new Server();
        server.startServer();
        System.out.println("Server started, version 0.16-SNAPSHOT");
        //Bind a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stopServer));
    }

}
