package io.pockethive.controlplane.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map.Entry;

public final class JsonFixtureAssertions {

    public static final String ANY_VALUE = "<<ANY>>";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private JsonFixtureAssertions() {
    }

    public static void assertMatchesFixture(String fixtureName, String json) throws IOException {
        try (InputStream input = JsonFixtureAssertions.class.getResourceAsStream(fixtureName)) {
            assertNotNull(input, () -> "Missing fixture " + fixtureName);
            JsonNode expected = MAPPER.readTree(input);
            JsonNode actual = MAPPER.readTree(json);
            assertNode(expected, actual, fixtureName);
        }
    }

    private static void assertNode(JsonNode expected, JsonNode actual, String path) {
        if (expected.isTextual() && ANY_VALUE.equals(expected.textValue())) {
            return;
        }
        assertEquals(expected.getNodeType(), actual.getNodeType(), () -> "Type mismatch at " + path);
        switch (expected.getNodeType()) {
            case OBJECT -> assertObject(expected, actual, path);
            case ARRAY -> assertArray(expected, actual, path);
            default -> assertEquals(expected, actual, () -> "Value mismatch at " + path);
        }
    }

    private static void assertObject(JsonNode expected, JsonNode actual, String path) {
        assertEquals(expected.size(), actual.size(), () -> "Field count mismatch at " + path + ": " + actual);
        Iterator<Entry<String, JsonNode>> fields = expected.fields();
        while (fields.hasNext()) {
            Entry<String, JsonNode> entry = fields.next();
            String childPath = path + "." + entry.getKey();
            JsonNode actualChild = actual.get(entry.getKey());
            assertNotNull(actualChild, () -> "Missing field " + childPath);
            assertNode(entry.getValue(), actualChild, childPath);
        }
    }

    private static void assertArray(JsonNode expected, JsonNode actual, String path) {
        assertEquals(expected.size(), actual.size(), () -> "Array length mismatch at " + path);
        for (int i = 0; i < expected.size(); i++) {
            assertNode(expected.get(i), actual.get(i), path + "[" + i + "]");
        }
    }
}
