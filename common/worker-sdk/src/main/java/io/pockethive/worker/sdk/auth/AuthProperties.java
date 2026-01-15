package io.pockethive.worker.sdk.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for auth system.
 */
@ConfigurationProperties(prefix = "pockethive.auth")
public class AuthProperties {
    
    private boolean enabled = true;
    private SchedulerProperties scheduler = new SchedulerProperties();
    private RefreshProperties refresh = new RefreshProperties();
    private HttpProperties http = new HttpProperties();
    private CleanupProperties cleanup = new CleanupProperties();
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public SchedulerProperties getScheduler() {
        return scheduler;
    }
    
    public void setScheduler(SchedulerProperties scheduler) {
        this.scheduler = scheduler;
    }
    
    public RefreshProperties getRefresh() {
        return refresh;
    }
    
    public void setRefresh(RefreshProperties refresh) {
        this.refresh = refresh;
    }
    
    public HttpProperties getHttp() {
        return http;
    }
    
    public void setHttp(HttpProperties http) {
        this.http = http;
    }
    
    public CleanupProperties getCleanup() {
        return cleanup;
    }
    
    public void setCleanup(CleanupProperties cleanup) {
        this.cleanup = cleanup;
    }
    
    public static class SchedulerProperties {
        private boolean enabled = true;
        private int scanIntervalSeconds = 10;
        private int threadPoolSize = 5;
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public int getScanIntervalSeconds() {
            return scanIntervalSeconds;
        }
        
        public void setScanIntervalSeconds(int scanIntervalSeconds) {
            this.scanIntervalSeconds = scanIntervalSeconds;
        }
        
        public int getThreadPoolSize() {
            return threadPoolSize;
        }
        
        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }
    }
    
    public static class RefreshProperties {
        private int defaultBuffer = 60;
        private int emergencyBuffer = 10;
        
        public int getDefaultBuffer() {
            return defaultBuffer;
        }
        
        public void setDefaultBuffer(int defaultBuffer) {
            this.defaultBuffer = defaultBuffer;
        }
        
        public int getEmergencyBuffer() {
            return emergencyBuffer;
        }
        
        public void setEmergencyBuffer(int emergencyBuffer) {
            this.emergencyBuffer = emergencyBuffer;
        }
    }
    
    public static class HttpProperties {
        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 10;
        private int maxConnections = 50;
        private boolean sslVerification = true;
        
        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }
        
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }
        
        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }
        
        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }
        
        public int getMaxConnections() {
            return maxConnections;
        }
        
        public void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }
        
        public boolean isSslVerification() {
            return sslVerification;
        }
        
        public void setSslVerification(boolean sslVerification) {
            this.sslVerification = sslVerification;
        }
    }
    
    public static class CleanupProperties {
        private boolean enabled = true;
        private boolean dryRun = false;
        private int inactiveThresholdHours = 24;
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public boolean isDryRun() {
            return dryRun;
        }
        
        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }
        
        public int getInactiveThresholdHours() {
            return inactiveThresholdHours;
        }
        
        public void setInactiveThresholdHours(int inactiveThresholdHours) {
            this.inactiveThresholdHours = inactiveThresholdHours;
        }
    }
}
