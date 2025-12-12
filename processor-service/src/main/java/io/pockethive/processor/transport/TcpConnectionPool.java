package io.pockethive.processor.transport;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class TcpConnectionPool {
  private final Map<String, Socket> connections = new ConcurrentHashMap<>();
  private final boolean sslVerify;

  public TcpConnectionPool(boolean sslVerify) {
    this.sslVerify = sslVerify;
  }

  public synchronized Socket getOrCreate(String host, int port, boolean useSsl, int timeout) throws Exception {
    String key = (useSsl ? "tcps://" : "tcp://") + host + ":" + port;
    Socket socket = connections.get(key);

    if (socket != null && !socket.isClosed() && socket.isConnected()) {
      return socket;
    }

    socket = useSsl ? createSslSocket(host, port, timeout) : createSocket(host, port, timeout);
    connections.put(key, socket);
    return socket;
  }

  public void remove(String host, int port, boolean useSsl) {
    String key = (useSsl ? "tcps://" : "tcp://") + host + ":" + port;
    Socket socket = connections.remove(key);
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException ignored) {}
    }
  }

  void closeAll() {
    connections.values().forEach(socket -> {
      try {
        socket.close();
      } catch (IOException ignored) {}
    });
    connections.clear();
  }

  private Socket createSocket(String host, int port, int timeout) throws IOException {
    Socket socket = new Socket(host, port);
    socket.setSoTimeout(timeout);
    socket.setKeepAlive(true);
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
    SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket(host, port);
    socket.setSoTimeout(timeout);
    socket.setKeepAlive(true);
    socket.startHandshake();
    return socket;
  }
}
