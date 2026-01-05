package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.TcpRequest;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class RequestStore {
    private final ConcurrentLinkedQueue<TcpRequest> requests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TcpRequest> unmatchedRequests = new ConcurrentLinkedQueue<>();

    public void addRequest(TcpRequest request) {
        requests.offer(request);
        while (requests.size() > 1000) {
            requests.poll();
        }
    }

    public void addUnmatchedRequest(TcpRequest request) {
        unmatchedRequests.offer(request);
        while (unmatchedRequests.size() > 1000) {
            unmatchedRequests.poll();
        }
    }

    public List<TcpRequest> getAllRequests() {
        return List.copyOf(requests);
    }

    public List<TcpRequest> getUnmatchedRequests() {
        return List.copyOf(unmatchedRequests);
    }

    public void clearRequests() {
        requests.clear();
        unmatchedRequests.clear();
    }
}
