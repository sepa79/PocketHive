package io.pockethive;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TopologyTest {
    private String loadGenQueue(String swarmId) throws Exception {
        System.setProperty("PH_SWARM_ID", swarmId);
        URL classes = Paths.get("target/classes").toUri().toURL();
        try (URLClassLoader cl = new URLClassLoader(new URL[]{classes}, null)) {
            Class<?> top = Class.forName("io.pockethive.Topology", true, cl);
            Field field = top.getField("GEN_QUEUE");
            return (String) field.get(null);
        } finally {
            System.clearProperty("PH_SWARM_ID");
        }
    }

    @Test
    void genQueueIncludesSwarmId() throws Exception {
        String q1 = loadGenQueue("swarmA");
        String q2 = loadGenQueue("swarmB");
        assertEquals("ph.gen.swarmA", q1);
        assertEquals("ph.gen.swarmB", q2);
        assertNotEquals(q1, q2);
    }
}
