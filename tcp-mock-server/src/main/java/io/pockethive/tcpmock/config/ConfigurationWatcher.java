package io.pockethive.tcpmock.config;

import org.springframework.stereotype.Component;

@Component
public class ConfigurationWatcher {
    private boolean watching = false;

    public void startWatching() { watching = true; }
    public void stopWatching() { watching = false; }
    public boolean isWatching() { return watching; }
}
