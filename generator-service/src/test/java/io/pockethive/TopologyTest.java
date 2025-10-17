package io.pockethive;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TopologyTest {
    private String loadGenQueue(String swarmId, String queue) throws Exception {
        URL classes = Paths.get("..", "common", "topology-core", "target", "classes").toUri().toURL();
        try (URLClassLoader cl = new URLClassLoader(new URL[]{classes}, null)) {
            setTopologyProperties(swarmId, queue);
            Class<?> top = Class.forName("io.pockethive.Topology", true, cl);
            Field field = top.getField("GEN_QUEUE");
            return (String) field.get(null);
        } finally {
            clearTopologyProperties();
        }
    }

    @Test
    void genQueueIncludesSwarmId() throws Exception {
        String q1 = loadGenQueue("swarmA", "swarmA.gen.queue");
        String q2 = loadGenQueue("swarmB", "swarmB.gen.queue");
        assertEquals("swarmA.gen.queue", q1);
        assertEquals("swarmB.gen.queue", q2);
        assertNotEquals(q1, q2);
    }

    private void setTopologyProperties(String swarmId, String generatorQueue) {
        System.setProperty("POCKETHIVE_CONTROL_PLANE_SWARM_ID", swarmId);
        System.setProperty("POCKETHIVE_TRAFFIC_EXCHANGE", "ph." + swarmId + ".hive");
        System.setProperty("POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR", generatorQueue);
        System.setProperty("POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR", swarmId + ".mod.queue");
        System.setProperty("POCKETHIVE_CONTROL_PLANE_QUEUES_FINAL", swarmId + ".final.queue");
        System.setProperty("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE", "ph.control");
        System.setProperty("POCKETHIVE_CONTROL_PLANE_EXCHANGE", "ph.control");
    }

    private void clearTopologyProperties() {
        System.clearProperty("POCKETHIVE_CONTROL_PLANE_SWARM_ID");
        System.clearProperty("POCKETHIVE_TRAFFIC_EXCHANGE");
        System.clearProperty("POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR");
        System.clearProperty("POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR");
        System.clearProperty("POCKETHIVE_CONTROL_PLANE_QUEUES_FINAL");
        System.clearProperty("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE");
        System.clearProperty("POCKETHIVE_CONTROL_PLANE_EXCHANGE");
    }
}
