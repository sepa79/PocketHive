package io.pockethive.processor.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class TcpConnectionPool {
  private static final int POOL_SIZE = 4;
  private final Map<String, ArrayBlockingQueue<Socket>> pools = new ConcurrentHashMap<>();
  private final boolean sslVerify;

  public TcpConnectionPool(boolean sslVerify) {
    this.sslVerify = sslVerify;
  }

  public Socket getOrCreate(String host,
                            int port,
                            boolean useSsl,
                            int timeout,
                            boolean keepAlive,
                            boolean tcpNoDelay) throws Exception {
    String key = (useSsl ? "tcps://" : "tcp://") + host + ":" + port;
    ArrayBlockingQueue<Socket> pool = pools.computeIfAbsent(key, k -> new ArrayBlockingQueue<>(POOL_SIZE));

    Socket socket = pool.poll();
    if (socket != null && !socket.isClosed() && socket.isConnected()) {
      configure(socket, timeout, keepAlive, tcpNoDelay);
      return socket;
    }

    socket = useSsl ? createSslSocket(host, port, timeout) : createSocket(host, port, timeout);
    configure(socket, timeout, keepAlive, tcpNoDelay);
    return socket;
  }

  public void returnToPool(String host, int port, boolean useSsl, Socket socket) {
    if (socket == null || socket.isClosed()) return;
    String key = (useSsl ? "tcps://" : "tcp://") + host + ":" + port;
    ArrayBlockingQueue<Socket> pool = pools.get(key);
    if (pool != null && !pool.offer(socket)) {
      try {
        socket.close();
      } catch (IOException ignored) {}
    }
  }

  public void remove(String host, int port, boolean useSsl) {
    String key = (useSsl ? "tcps://" : "tcp://") + host + ":" + port;
    ArrayBlockingQueue<Socket> pool = pools.remove(key);
    if (pool != null) {
      pool.forEach(socket -> {
        try {
          socket.close();
        } catch (IOException ignored) {}
      });
    }
  }

  void closeAll() {
    pools.values().forEach(pool -> pool.forEach(socket -> {
      try {
        socket.close();
      } catch (IOException ignored) {}
    }));
    pools.clear();
  }

  private Socket createSocket(String host, int port, int timeout) throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress(host, port), timeout);
    return socket;
  }

  private Socket createSslSocket(String host, int port, int timeout) throws Exception {
    SSLContext sslContext = SSLContext.getInstance("TLS");
    TrustManager[] trustManagers = sslVerify ? null : new TrustManager[]{
        new X509TrustManager() {
          public X509Certificate[] getAcceptedIssuers() { return null; }
          public void checkClientTrusted(X509Certificate[] certs, String authType) {}
          public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        }
    };
    sslContext.init(null, trustManagers, new java.security.SecureRandom());
    SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();
    socket.connect(new InetSocketAddress(host, port), timeout);
    socket.startHandshake();
    return socket;
  }

  private static void configure(Socket socket, int timeout, boolean keepAlive, boolean tcpNoDelay) throws IOException {
    socket.setSoTimeout(timeout);
    socket.setKeepAlive(keepAlive);
    socket.setTcpNoDelay(tcpNoDelay);
  }
}
