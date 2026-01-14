package io.pockethive.tcpmock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tcp-mock")
public class TcpMockConfig {
  private int port = 8080;
  private String nodeId = "tcp-mock-default";
  private Dashboard dashboard = new Dashboard();
  private Validation validation = new Validation();
  private Filtering filtering = new Filtering();
  private Ssl ssl = new Ssl();
  private Connection connection = new Connection();

  public int getPort() { return port; }
  public void setPort(int port) { this.port = port; }
  public String getNodeId() { return nodeId; }
  public void setNodeId(String nodeId) { this.nodeId = nodeId; }
  public Dashboard getDashboard() { return dashboard; }
  public Validation getValidation() { return validation; }
  public Filtering getFiltering() { return filtering; }
  public Ssl getSsl() { return ssl; }
  public Connection getConnection() { return connection; }

  public static class Dashboard {
    private boolean enabled = true;
    private String username = "admin";
    private String password = "admin";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
  }

  public static class Validation {
    private boolean enabled = true;
    private int maxMessageSize = 8192;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxMessageSize() { return maxMessageSize; }
    public void setMaxMessageSize(int maxMessageSize) { this.maxMessageSize = maxMessageSize; }
  }

  public static class Filtering {
    private boolean enabled = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
  }

  public static class Ssl {
    private boolean enabled = false;
    private String keyStore;
    private String keyStorePassword;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getKeyStore() { return keyStore; }
    public void setKeyStore(String keyStore) { this.keyStore = keyStore; }
    public String getKeyStorePassword() { return keyStorePassword; }
    public void setKeyStorePassword(String keyStorePassword) { this.keyStorePassword = keyStorePassword; }
  }

  public static class Connection {
    private int backlog = 128;
    private int idleTimeout = 300;
    private int maxConnections = 1000;

    public int getBacklog() { return backlog; }
    public void setBacklog(int backlog) { this.backlog = backlog; }
    public int getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(int idleTimeout) { this.idleTimeout = idleTimeout; }
    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
  }
}
