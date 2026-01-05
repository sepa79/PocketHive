package io.pockethive.tcpmock.util;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.Socket;
import java.util.Random;

@Component
public class FaultInjector {
    private final Random random = new Random();

    public enum Fault {
        CONNECTION_RESET_BY_PEER,
        EMPTY_RESPONSE,
        MALFORMED_RESPONSE_CHUNK,
        RANDOM_DATA_THEN_CLOSE,
        SOCKET_TIMEOUT
    }

    public void injectFault(Socket socket, Fault fault) throws IOException {
        switch (fault) {
            case CONNECTION_RESET_BY_PEER:
                socket.setSoLinger(true, 0);
                socket.close();
                break;
            case EMPTY_RESPONSE:
                // Send nothing and close
                socket.close();
                break;
            case MALFORMED_RESPONSE_CHUNK:
                socket.getOutputStream().write("MALFORMED\r\n".getBytes());
                socket.getOutputStream().flush();
                socket.close();
                break;
            case RANDOM_DATA_THEN_CLOSE:
                byte[] randomData = new byte[random.nextInt(100) + 10];
                random.nextBytes(randomData);
                socket.getOutputStream().write(randomData);
                socket.getOutputStream().flush();
                socket.close();
                break;
            case SOCKET_TIMEOUT:
                try {
                    Thread.sleep(30000); // Force timeout
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                socket.close();
                break;
        }
    }

    public boolean shouldInjectFault(double faultRate) {
        return random.nextDouble() < (faultRate / 100.0);
    }
}
