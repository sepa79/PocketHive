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
    
    public static class SchedulerProperties {
        private boolean enabled = true;
        private int scanIntervalSeconds = 10;
        
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
        
    }
    
    public static class RefreshProperties {
        private int refreshAheadSeconds = 60;
        private int emergencyRefreshAheadSeconds = 10;
        
        public int getRefreshAheadSeconds() {
            return refreshAheadSeconds;
        }
        
        public void setRefreshAheadSeconds(int refreshAheadSeconds) {
            this.refreshAheadSeconds = refreshAheadSeconds;
        }
        
        public int getEmergencyRefreshAheadSeconds() {
            return emergencyRefreshAheadSeconds;
        }
        
        public void setEmergencyRefreshAheadSeconds(int emergencyRefreshAheadSeconds) {
            this.emergencyRefreshAheadSeconds = emergencyRefreshAheadSeconds;
        }
    }
    
    public static class HttpProperties {
        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 10;
        
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
        
    }
}
