package io.pockethive.processor.transport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NioTransportTest {

  @Test
  void readTimeoutTriggersWhenNoResponse() throws Exception {
    byte[] payload = "ping".getBytes(StandardCharsets.UTF_8);

    try (SilentServer server = new SilentServer()) {
      TcpTransport transport = new NioTransport();
      TcpRequest request = new TcpRequest(
          "127.0.0.1",
          server.port(),
          payload,
          Map.of(
              "connectTimeoutMs", 1_000,
              "readTimeoutMs", 200,
              "maxBytes", 8_192
          )
      );

      assertThatThrownBy(() -> transport.execute(request, TcpBehavior.REQUEST_RESPONSE))
          .isInstanceOf(TcpException.class);

      server.await(Duration.ofSeconds(2));
    }
  }

  private static final class SilentServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final CountDownLatch handled = new CountDownLatch(1);
    private final Thread acceptThread;

    private SilentServer() throws Exception {
      this.serverSocket = new ServerSocket(0);
      this.serverSocket.setReuseAddress(true);
      this.acceptThread = new Thread(this::acceptLoop, "nio-transport-test-server");
      this.acceptThread.setDaemon(true);
      this.acceptThread.start();
    }

    int port() {
      return serverSocket.getLocalPort();
    }

    boolean await(Duration timeout) throws InterruptedException {
      return handled.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void acceptLoop() {
      try (Socket socket = serverSocket.accept();
           InputStream in = socket.getInputStream();
           OutputStream out = socket.getOutputStream()) {
        in.read();
        out.flush();
        Thread.sleep(1_000);
      } catch (Exception ignored) {
      } finally {
        handled.countDown();
      }
    }

    @Override
    public void close() throws Exception {
      try {
        serverSocket.close();
      } finally {
        acceptThread.join(500);
      }
    }
  }
}
