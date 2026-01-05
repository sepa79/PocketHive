package io.pockethive.tcpmock.service;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ClusterCoordinator {
    private final List<String> activeNodes = new CopyOnWriteArrayList<>();
    private String nodeId = "node-1";

    public void startCluster() {
        activeNodes.add(nodeId);
    }

    public String getNodeId() { return nodeId; }
    public int getClusterSize() { return activeNodes.size(); }
    public List<String> getActiveNodes() { return List.copyOf(activeNodes); }
}
