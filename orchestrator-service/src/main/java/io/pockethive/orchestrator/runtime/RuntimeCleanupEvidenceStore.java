package io.pockethive.orchestrator.runtime;

import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Evidence;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class RuntimeCleanupEvidenceStore {
    private final Map<String, Evidence> evidenceByIdempotency = new ConcurrentHashMap<>();

    public Optional<Evidence> findEvidence(String idempotencyKey, String actor) {
        return Optional.ofNullable(evidenceByIdempotency.get(evidenceKey(idempotencyKey, actor)));
    }

    public void saveEvidence(Evidence evidence) {
        evidenceByIdempotency.put(evidenceKey(evidence.idempotencyKey(), evidence.actor()), evidence);
    }

    public static boolean sameCleanupInput(Evidence evidence, String candidateSetHash, List<String> candidateIds) {
        return evidence.candidateSetHash().equals(candidateSetHash)
            && canonicalCandidateIds(evidence.candidateIds()).equals(canonicalCandidateIds(candidateIds));
    }

    private static String evidenceKey(String idempotencyKey, String actor) {
        return actor + "|" + idempotencyKey;
    }

    private static String canonicalCandidateIds(List<String> candidateIds) {
        return candidateIds.stream().sorted().collect(Collectors.joining(","));
    }
}
