package io.pockethive.processor.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.net.InetSocketAddress;
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

class NettyTransportTest {

  @Test
  void echoReadsBytesInEchoMode() throws Exception {
    byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
    try (TestServer server = TestServer.echo(payload.length)) {
      TcpTransport transport = new NettyTransport();
      TcpRequest request = new TcpRequest(
          "127.0.0.1",
          server.port(),
          payload,
          Map.of(
              "timeout", 1_000,
              "maxBytes", 8_192,
              "ssl", false,
              "sslVerify", false
          )
      );

      TcpResponse response = transport.execute(request, TcpBehavior.ECHO);

      assertThat(response.status()).isEqualTo(200);
      assertThat(response.body()).isEqualTo(payload);
      assertThat(server.await(Duration.ofSeconds(2))).isTrue();
    }
  }

  @Test
  void streamingStopsAtMaxBytes() throws Exception {
    byte[] payload = "start".getBytes(StandardCharsets.UTF_8);
    int maxBytes = 32;
    try (TestServer server = TestServer.streaming(payload.length, maxBytes + 64)) {
      TcpTransport transport = new NettyTransport();
      TcpRequest request = new TcpRequest(
          "127.0.0.1",
          server.port(),
          payload,
          Map.of(
              "timeout", 1_000,
              "maxBytes", maxBytes,
              "ssl", false,
              "sslVerify", false
          )
      );

      TcpResponse response = transport.execute(request, TcpBehavior.STREAMING);

      assertThat(response.status()).isEqualTo(200);
      assertThat(response.body()).hasSize(maxBytes);
      assertThat(server.await(Duration.ofSeconds(2))).isTrue();
    }
  }

  @Test
  void requestResponseIncludesEndTag() throws Exception {
    String endTag = "</Document>";
    String response = "<Document>ok</Document>";

    try (TestServer server = TestServer.requestResponse(response)) {
      TcpTransport transport = new NettyTransport();
      TcpRequest request = new TcpRequest(
          "127.0.0.1",
          server.port(),
          "<Document>ping</Document>".getBytes(StandardCharsets.UTF_8),
          Map.of(
              "timeout", 1_000,
              "maxBytes", 8_192,
              "endTag", endTag,
              "ssl", false,
              "sslVerify", false
          )
      );

      TcpResponse result = transport.execute(request, TcpBehavior.REQUEST_RESPONSE);

      assertThat(result.status()).isEqualTo(200);
      assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo(response);
      assertThat(server.await(Duration.ofSeconds(2))).isTrue();
    }
  }

  @Test
  void requestResponseWorksOverTlsWhenSslVerifyDisabled() throws Exception {
    String endTag = "</Document>";
    String response = "<Document>ok</Document>";

    try (TlsTestServer server = TlsTestServer.requestResponse(endTag, response)) {
      TcpTransport transport = new NettyTransport();
      TcpRequest request = new TcpRequest(
          "127.0.0.1",
          server.port(),
          "<Document>ping</Document>".getBytes(StandardCharsets.UTF_8),
          Map.of(
              "timeout", 2_000,
              "maxBytes", 8_192,
              "endTag", endTag,
              "ssl", true,
              "sslVerify", false
          )
      );

      TcpResponse result = transport.execute(request, TcpBehavior.REQUEST_RESPONSE);

      assertThat(result.status()).isEqualTo(200);
      assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo(response);
      assertThat(server.await(Duration.ofSeconds(2))).isTrue();
    }
  }

  @Test
  void requestResponseFailsOverTlsWhenSslVerifyEnabled() throws Exception {
    String endTag = "</Document>";
    String response = "<Document>ok</Document>";

    try (TlsTestServer server = TlsTestServer.requestResponse(endTag, response)) {
      TcpTransport transport = new NettyTransport();
      TcpRequest request = new TcpRequest(
          "127.0.0.1",
          server.port(),
          "<Document>ping</Document>".getBytes(StandardCharsets.UTF_8),
          Map.of(
              "timeout", 2_000,
              "maxBytes", 8_192,
              "endTag", endTag,
              "ssl", true,
              "sslVerify", true
          )
      );

      assertThatThrownBy(() -> transport.execute(request, TcpBehavior.REQUEST_RESPONSE))
          .isInstanceOf(TcpException.class);
    }
  }

  private static final class TestServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final CountDownLatch handled = new CountDownLatch(1);
    private final Thread acceptThread;
    private final Handler handler;

    private TestServer(Handler handler) throws Exception {
      this.handler = handler;
      this.serverSocket = new ServerSocket(0);
      this.serverSocket.setReuseAddress(true);
      this.acceptThread = new Thread(this::acceptLoop, "netty-transport-test-server");
      this.acceptThread.setDaemon(true);
      this.acceptThread.start();
    }

    static TestServer echo(int expectedBytes) throws Exception {
      return new TestServer((socket, in, out) -> {
        byte[] requestBytes = readExactly(in, expectedBytes);
        out.write(requestBytes);
        out.flush();
      });
    }

    static TestServer streaming(int expectedRequestBytes, int responseBytes) throws Exception {
      return new TestServer((socket, in, out) -> {
        readExactly(in, expectedRequestBytes);
        byte[] data = new byte[responseBytes];
        for (int i = 0; i < data.length; i++) {
          data[i] = 'a';
        }
        out.write(data);
        out.flush();
      });
    }

    static TestServer requestResponse(String response) throws Exception {
      return new TestServer((socket, in, out) -> {
        byte[] buffer = new byte[256];
        // just consume some bytes, then reply
        in.read(buffer);
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
      });
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
        handler.handle(socket, in, out);
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

    private interface Handler {
      void handle(Socket socket, InputStream in, OutputStream out) throws Exception;
    }

    private static byte[] readExactly(InputStream in, int expected) throws Exception {
      byte[] out = new byte[expected];
      int offset = 0;
      while (offset < expected) {
        int read = in.read(out, offset, expected - offset);
        if (read < 0) {
          break;
        }
        offset += read;
      }
      if (offset == expected) {
        return out;
      }
      byte[] truncated = new byte[offset];
      System.arraycopy(out, 0, truncated, 0, offset);
      return truncated;
    }
  }

  private static final class TlsTestServer implements AutoCloseable {
    private final SelfSignedCertificate certificate;
    private final NioEventLoopGroup boss;
    private final NioEventLoopGroup worker;
    private final Channel channel;
    private final CountDownLatch handled;

    private TlsTestServer(SelfSignedCertificate certificate,
                          NioEventLoopGroup boss,
                          NioEventLoopGroup worker,
                          Channel channel,
                          CountDownLatch handled) {
      this.certificate = certificate;
      this.boss = boss;
      this.worker = worker;
      this.channel = channel;
      this.handled = handled;
    }

    static TlsTestServer requestResponse(String endTag, String response) throws Exception {
      SelfSignedCertificate certificate = new SelfSignedCertificate();
      SslContext sslContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build();
      CountDownLatch handled = new CountDownLatch(1);

      NioEventLoopGroup boss = new NioEventLoopGroup(1);
      NioEventLoopGroup worker = new NioEventLoopGroup(1);

      ServerBootstrap bootstrap = new ServerBootstrap()
          .group(boss, worker)
          .channel(NioServerSocketChannel.class)
          .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
              ChannelPipeline pipeline = ch.pipeline();
              pipeline.addLast(sslContext.newHandler(ch.alloc()));
              pipeline.addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                private final byte[] endTagBytes = endTag.getBytes(StandardCharsets.UTF_8);
                private final byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                private int matchIdx = 0;

                @Override
                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                  while (msg.isReadable()) {
                    byte b = msg.readByte();
                    if (b == endTagBytes[matchIdx]) {
                      matchIdx++;
                      if (matchIdx == endTagBytes.length) {
                        ctx.writeAndFlush(ctx.alloc().buffer(responseBytes.length).writeBytes(responseBytes));
                        handled.countDown();
                        ctx.close();
                        return;
                      }
                    } else {
                      matchIdx = 0;
                    }
                  }
                }
              });
              pipeline.addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                  ctx.close();
                }
              });
            }
          });

      Channel channel = bootstrap.bind(0).sync().channel();
      return new TlsTestServer(certificate, boss, worker, channel, handled);
    }

    int port() {
      return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    boolean await(Duration timeout) throws InterruptedException {
      return handled.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws Exception {
      try {
        channel.close().sync();
      } finally {
        boss.shutdownGracefully().sync();
        worker.shutdownGracefully().sync();
        certificate.delete();
      }
    }
  }
}
