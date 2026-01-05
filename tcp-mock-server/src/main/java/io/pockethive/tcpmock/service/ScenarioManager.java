package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.TcpRequest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ScenarioManager {
    private final Map<String, String> scenarioStates = new ConcurrentHashMap<>();

    public String getScenarioState(String scenarioName) {
        return scenarioStates.get(scenarioName);
    }

    public void setScenarioState(String scenarioName, String state) {
        scenarioStates.put(scenarioName, state);
    }

    public Map<String, String> getAllScenarios() {
        return Map.copyOf(scenarioStates);
    }

    public void resetAllScenarios() {
        scenarioStates.clear();
    }

    public void removeScenario(String scenarioName) {
        scenarioStates.remove(scenarioName);
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
