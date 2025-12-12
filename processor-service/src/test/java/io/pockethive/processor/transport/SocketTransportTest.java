package io.pockethive.processor.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.processor.TcpTransportConfig;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SocketTransportTest {

  @Test
  void reusesConnectionWhenConfigured() throws Exception {
    try (TestTcpServer server = new TestTcpServer(2)) {
      server.start();

      TcpTransportConfig config = new TcpTransportConfig(
          "socket",
          1_000,
          8192,
          true,
          4,
          true,
          false,
          TcpTransportConfig.ConnectionReuse.GLOBAL,
          0
      );
      SocketTransport transport = new SocketTransport(config);

      Map<String, Object> options = Map.of(
          "endTag", "</Document>",
          "timeout", 1_000,
          "maxBytes", 8192,
          "ssl", false,
          "sslVerify", false
      );
      TcpRequest request = new TcpRequest(
          "127.0.0.1",
          server.port(),
          "<Document>ping</Document>".getBytes(StandardCharsets.UTF_8),
          options
      );

      TcpResponse first = transport.execute(request, TcpBehavior.REQUEST_RESPONSE);
      TcpResponse second = transport.execute(request, TcpBehavior.REQUEST_RESPONSE);

      assertThat(new String(first.body(), StandardCharsets.UTF_8)).isEqualTo("<Document>ok</Document>");
      assertThat(new String(second.body(), StandardCharsets.UTF_8)).isEqualTo("<Document>ok</Document>");
      assertThat(server.awaitAllMessages(Duration.ofSeconds(2))).isTrue();
      assertThat(server.connectionCount()).isEqualTo(1);
    }
  }

  @Test
  void opensNewConnectionWhenReuseDisabled() throws Exception {
    try (TestTcpServer server = new TestTcpServer(2)) {
      server.start();

      TcpTransportConfig config = new TcpTransportConfig(
          "socket",
          1_000,
          8192,
          true,
          4,
          true,
          false,
          TcpTransportConfig.ConnectionReuse.NONE,
          0
      );
      SocketTransport transport = new SocketTransport(config);

      Map<String, Object> options = Map.of(
          "endTag", "</Document>",
          "timeout", 1_000,
          "maxBytes", 8192,
          "ssl", false,
          "sslVerify", false
      );
      TcpRequest request = new TcpRequest(
          "127.0.0.1",
          server.port(),
          "<Document>ping</Document>".getBytes(StandardCharsets.UTF_8),
          options
      );

      transport.execute(request, TcpBehavior.REQUEST_RESPONSE);
      transport.execute(request, TcpBehavior.REQUEST_RESPONSE);

      assertThat(server.awaitAllMessages(Duration.ofSeconds(2))).isTrue();
      assertThat(server.connectionCount()).isEqualTo(2);
    }
  }

  private static final class TestTcpServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final Set<Socket> clientSockets = ConcurrentHashMap.newKeySet();
    private final CountDownLatch messageLatch;
    private volatile Thread acceptThread;

    private TestTcpServer(int expectedMessages) throws Exception {
      this.serverSocket = new ServerSocket(0);
      this.serverSocket.setReuseAddress(true);
      this.messageLatch = new CountDownLatch(expectedMessages);
    }

    int port() {
      return serverSocket.getLocalPort();
    }

    int connectionCount() {
      return connectionCount.get();
    }

    void start() {
      acceptThread = new Thread(this::acceptLoop, "test-tcp-accept");
      acceptThread.setDaemon(true);
      acceptThread.start();
    }

    boolean awaitAllMessages(Duration timeout) throws InterruptedException {
      return messageLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void acceptLoop() {
      while (!serverSocket.isClosed()) {
        try {
          Socket socket = serverSocket.accept();
          clientSockets.add(socket);
          connectionCount.incrementAndGet();
          Thread handler = new Thread(() -> handle(socket), "test-tcp-handler");
          handler.setDaemon(true);
          handler.start();
        } catch (Exception ignored) {
          return;
        }
      }
    }

    private void handle(Socket socket) {
      try (socket;
           InputStream in = socket.getInputStream();
           OutputStream out = socket.getOutputStream()) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[256];
        while (messageLatch.getCount() > 0) {
          int read = in.read(chunk);
          if (read < 0) {
            return;
          }
          buffer.write(chunk, 0, read);
          String data = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
          int idx = data.indexOf("</Document>");
          if (idx < 0) {
            continue;
          }
          String remaining = data.substring(idx + "</Document>".length());
          buffer.reset();
          buffer.write(remaining.getBytes(StandardCharsets.UTF_8));
          out.write("<Document>ok</Document>".getBytes(StandardCharsets.UTF_8));
          out.flush();
          messageLatch.countDown();
        }
      } catch (Exception ignored) {
      }
    }

    @Override
    public void close() throws Exception {
      try {
        serverSocket.close();
      } finally {
        for (Socket socket : clientSockets) {
          try {
            socket.close();
          } catch (Exception ignored) {
          }
        }
        if (acceptThread != null) {
          acceptThread.join(500);
        }
      }
    }
  }
}
