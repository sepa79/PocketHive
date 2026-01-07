package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.TcpRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ScenarioManager {
    private final Map<String, String> scenarioStates = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String scenariosFile = "/app/data/scenarios-state.json";
    private final String defaultScenariosFile = "/app/scenarios-state-default.json";

    public ScenarioManager() {
        loadScenarios();
    }

    private void loadScenarios() {
        try {
            Path path = Paths.get(scenariosFile);
            if (Files.exists(path)) {
                String content = Files.readString(path);
                @SuppressWarnings("unchecked")
                Map<String, String> loaded = mapper.readValue(content, Map.class);
                scenarioStates.putAll(loaded);
                System.out.println("Loaded " + loaded.size() + " scenarios from disk");
            } else {
                // Load defaults if no persisted state exists
                Path defaultPath = Paths.get(defaultScenariosFile);
                if (Files.exists(defaultPath)) {
                    String content = Files.readString(defaultPath);
                    @SuppressWarnings("unchecked")
                    Map<String, String> loaded = mapper.readValue(content, Map.class);
                    scenarioStates.putAll(loaded);
                    System.out.println("Loaded " + loaded.size() + " default scenarios");
                    saveScenarios(); // Persist defaults
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load scenarios: " + e.getMessage());
        }
    }

    private void saveScenarios() {
        try {
            Path path = Paths.get(scenariosFile);
            Files.createDirectories(path.getParent());
            String content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(scenarioStates);
            Files.writeString(path, content);
        } catch (IOException e) {
            System.err.println("Failed to save scenarios: " + e.getMessage());
        }
    }

    public String getScenarioState(String scenarioName) {
        return scenarioStates.get(scenarioName);
    }

    public void setScenarioState(String scenarioName, String state) {
        scenarioStates.put(scenarioName, state);
        saveScenarios();
    }

    public Map<String, String> getAllScenarios() {
        return Map.copyOf(scenarioStates);
    }

    public void resetAllScenarios() {
        scenarioStates.clear();
    }

    public void removeScenario(String scenarioName) {
        scenarioStates.remove(scenarioName);
        saveScenarios();
    }

    @Component
    public static class RequestStore {

        private final ConcurrentLinkedQueue<TcpRequest> requests = new ConcurrentLinkedQueue<>();
        private final List<RequestListener> listeners = new CopyOnWriteArrayList<>();
        private static final int MAX_REQUESTS = 1000;

        public void addRequest(TcpRequest request) {
            requests.offer(request);

            // Keep only last MAX_REQUESTS
            while (requests.size() > MAX_REQUESTS) {
                requests.poll();
            }

            // Notify listeners
            listeners.forEach(listener -> listener.onRequest(request));
        }

        public List<TcpRequest> getAllRequests() {
            return List.copyOf(requests);
        }

        public void clear() {
            requests.clear();
        }

        public void addListener(RequestListener listener) {
            listeners.add(listener);
        }

        public void removeListener(RequestListener listener) {
            listeners.remove(listener);
        }

        public interface RequestListener {
            void onRequest(TcpRequest request);
        }
    }
}
