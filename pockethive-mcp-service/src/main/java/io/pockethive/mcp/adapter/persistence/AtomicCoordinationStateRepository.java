package io.pockethive.mcp.adapter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.application.CoordinationStateRepository;
import io.pockethive.mcp.application.ToolExecutionException;
import io.pockethive.mcp.application.UploadCoordinationSnapshot;
import io.pockethive.mcp.config.McpStateMode;
import io.pockethive.mcp.domain.AgentSession;
import io.pockethive.mcp.domain.AgentSessionSnapshot;
import io.pockethive.mcp.domain.AgentSessionState;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import io.pockethive.mcp.domain.ScenarioWorkflowSnapshot;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.time.Duration;
import java.time.Instant;

/**
 * Responsibility: Persist coordination state atomically under an exclusive process lock.
 * Must not: Own domain transitions or expose persistence details through public contracts.
 * Contract: docs/mcp/README.md.
 */

public final class AtomicCoordinationStateRepository implements CoordinationStateRepository, AutoCloseable {
    private static final String STATE_FILE = "state.json";
    private static final String LOCK_FILE = "state.lock";
    private static final CoordinationStateSchema STATE_SCHEMA = new CoordinationStateSchema();

    private final ObjectMapper mapper;
    private final McpStateMode mode;
    private final Path stateDirectory;
    private final long maxStateBytes;
    private final int maxOpenSessions;
    private final int maxOpenSessionsPerPrincipal;
    private FileChannel lockChannel;
    private FileLock lock;
    private CoordinationStateDocument state;

    public AtomicCoordinationStateRepository(ObjectMapper mapper,
                                              McpStateMode mode,
                                              Path stateDirectory,
                                              long maxStateBytes,
                                              int maxOpenSessions,
                                              int maxOpenSessionsPerPrincipal) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
        this.stateDirectory = java.util.Objects.requireNonNull(stateDirectory, "stateDirectory");
        if (maxStateBytes < 1 || maxOpenSessions < 1 || maxOpenSessionsPerPrincipal < 1
            || maxOpenSessionsPerPrincipal > maxOpenSessions) {
            throw new IllegalArgumentException("MCP_STATE_CONFIGURATION_INVALID");
        }
        this.maxStateBytes = maxStateBytes;
        this.maxOpenSessions = maxOpenSessions;
        this.maxOpenSessionsPerPrincipal = maxOpenSessionsPerPrincipal;
        this.state = CoordinationStateDocument.empty();
        if (mode == McpStateMode.FILE) {
            initialiseFileStore();
        }
    }

    @Override
    public synchronized Optional<AgentSession> findSession(String sessionId) {
        return Optional.ofNullable(state.sessions().get(sessionId)).map(AgentSession::restore);
    }

    @Override
    public synchronized Optional<ScenarioWorkflow> findWorkflow(String workflowId) {
        return Optional.ofNullable(state.workflows().get(workflowId)).map(ScenarioWorkflow::restore);
    }

    @Override
    public synchronized List<ScenarioWorkflow> findWorkflows(List<String> workflowIds) {
        return workflowIds.stream()
            .map(state.workflows()::get)
            .filter(java.util.Objects::nonNull)
            .map(ScenarioWorkflow::restore)
            .toList();
    }

    @Override
    public synchronized List<Map<String, Object>> findGeneratedFiles(String workflowId) {
        return copyFiles(state.generatedFiles().getOrDefault(workflowId, List.of()));
    }

    @Override
    public synchronized void createSession(AgentSession session) {
        if (state.sessions().containsKey(session.id())) {
            throw new ToolExecutionException("AGENT_SESSION_ALREADY_EXISTS", session.id());
        }
        if (openSessionCount(state, null) >= maxOpenSessions
            || openSessionCount(state, session.principal()) >= maxOpenSessionsPerPrincipal) {
            throw new ToolExecutionException("AGENT_SESSION_LIMIT_REACHED", session.principal().subject());
        }
        Map<String, AgentSessionSnapshot> sessions = new TreeMap<>(state.sessions());
        sessions.put(session.id(), session.snapshot());
        replace(new CoordinationStateDocument(CoordinationStateSchema.CURRENT_VERSION, sessions, state.workflows(), state.generatedFiles(),
            state.uploadCoordination()));
    }

    @Override
    public synchronized void saveSession(AgentSession session) {
        requirePresent(state.sessions(), session.id(), "AGENT_SESSION_NOT_FOUND");
        Map<String, AgentSessionSnapshot> sessions = new TreeMap<>(state.sessions());
        sessions.put(session.id(), session.snapshot());
        replace(new CoordinationStateDocument(CoordinationStateSchema.CURRENT_VERSION, sessions, state.workflows(), state.generatedFiles(),
            state.uploadCoordination()));
    }

    @Override
    public synchronized void createWorkflow(AgentSession session, ScenarioWorkflow workflow) {
        requirePresent(state.sessions(), session.id(), "AGENT_SESSION_NOT_FOUND");
        if (state.workflows().containsKey(workflow.id())) {
            throw new ToolExecutionException("SCENARIO_WORKFLOW_ALREADY_EXISTS", workflow.id());
        }
        if (!session.workflowIds().contains(workflow.id()) || !session.id().equals(workflow.agentSessionId())
            || !session.principal().equals(workflow.principal())) {
            throw new ToolExecutionException("SCENARIO_WORKFLOW_SESSION_MISMATCH", workflow.id());
        }
        Map<String, AgentSessionSnapshot> sessions = new TreeMap<>(state.sessions());
        sessions.put(session.id(), session.snapshot());
        Map<String, ScenarioWorkflowSnapshot> workflows = new TreeMap<>(state.workflows());
        workflows.put(workflow.id(), workflow.snapshot());
        replace(new CoordinationStateDocument(CoordinationStateSchema.CURRENT_VERSION, sessions, workflows, state.generatedFiles(),
            state.uploadCoordination()));
    }

    @Override
    public synchronized void saveWorkflow(ScenarioWorkflow workflow, List<Map<String, Object>> generatedFiles) {
        requirePresent(state.workflows(), workflow.id(), "SCENARIO_WORKFLOW_NOT_FOUND");
        Map<String, ScenarioWorkflowSnapshot> workflows = new TreeMap<>(state.workflows());
        workflows.put(workflow.id(), workflow.snapshot());
        Map<String, List<Map<String, Object>>> files = new TreeMap<>(state.generatedFiles());
        files.put(workflow.id(), copyFiles(generatedFiles));
        replace(new CoordinationStateDocument(CoordinationStateSchema.CURRENT_VERSION, state.sessions(), workflows, files,
            state.uploadCoordination()));
    }

    @Override
    public synchronized void saveWorkflow(ScenarioWorkflow workflow) {
        requirePresent(state.workflows(), workflow.id(), "SCENARIO_WORKFLOW_NOT_FOUND");
        Map<String, ScenarioWorkflowSnapshot> workflows = new TreeMap<>(state.workflows());
        workflows.put(workflow.id(), workflow.snapshot());
        replace(new CoordinationStateDocument(CoordinationStateSchema.CURRENT_VERSION, state.sessions(), workflows, state.generatedFiles(),
            state.uploadCoordination()));
    }

    @Override
    public synchronized void saveWorkflowAndRemoveGeneratedFiles(ScenarioWorkflow workflow) {
        requirePresent(state.workflows(), workflow.id(), "SCENARIO_WORKFLOW_NOT_FOUND");
        Map<String, ScenarioWorkflowSnapshot> workflows = new TreeMap<>(state.workflows());
        workflows.put(workflow.id(), workflow.snapshot());
        Map<String, List<Map<String, Object>>> files = new TreeMap<>(state.generatedFiles());
        files.remove(workflow.id());
        replace(new CoordinationStateDocument(CoordinationStateSchema.CURRENT_VERSION, state.sessions(), workflows, files,
            state.uploadCoordination()));
    }

    @Override
    public synchronized long countOpenSessions(PrincipalKey principal) {
        return openSessionCount(state, principal);
    }

    @Override
    public synchronized UploadCoordinationSnapshot loadUploadCoordination() {
        return state.uploadCoordination();
    }

    @Override
    public synchronized void saveUploadCoordination(UploadCoordinationSnapshot uploadCoordination) {
        replace(new CoordinationStateDocument(CoordinationStateSchema.CURRENT_VERSION, state.sessions(), state.workflows(), state.generatedFiles(),
            uploadCoordination));
    }

    @Override
    public synchronized void maintainSessions(Instant now, Duration terminalRetention) {
        java.util.Objects.requireNonNull(now, "now");
        if (terminalRetention == null || terminalRetention.isNegative()) {
            throw new IllegalArgumentException("terminalRetention must not be negative");
        }
        Map<String, AgentSessionSnapshot> sessions = new TreeMap<>();
        java.util.Set<String> retainedSessionIds = new java.util.HashSet<>();
        boolean changed = false;
        for (AgentSessionSnapshot snapshot : state.sessions().values()) {
            AgentSession session = AgentSession.restore(snapshot);
            session.expireAt(now);
            boolean retain = switch (session.state()) {
                case OPEN -> true;
                case CLOSED -> now.isBefore(session.closedAt().plus(terminalRetention));
                case EXPIRED -> now.isBefore(session.expiresAt().plus(terminalRetention));
            };
            if (retain) {
                sessions.put(session.id(), session.snapshot());
                retainedSessionIds.add(session.id());
            }
            changed |= !snapshot.equals(session.snapshot()) || !retain;
        }
        if (!changed) {
            return;
        }
        Map<String, ScenarioWorkflowSnapshot> workflows = new TreeMap<>();
        Map<String, List<Map<String, Object>>> files = new TreeMap<>();
        state.workflows().forEach((id, workflow) -> {
            if (retainedSessionIds.contains(workflow.agentSessionId())) {
                workflows.put(id, workflow);
                if (state.generatedFiles().containsKey(id)) {
                    files.put(id, state.generatedFiles().get(id));
                }
            }
        });
        replace(new CoordinationStateDocument(CoordinationStateSchema.CURRENT_VERSION, sessions, workflows, files, state.uploadCoordination()));
    }

    private void replace(CoordinationStateDocument candidate) {
        byte[] encoded;
        try {
            encoded = mapper.writeValueAsBytes(candidate);
        } catch (IOException exception) {
            throw new IllegalStateException("MCP_STATE_SERIALIZATION_FAILED", exception);
        }
        if (encoded.length > maxStateBytes) {
            throw new ToolExecutionException("MCP_STATE_CAPACITY_EXCEEDED", String.valueOf(maxStateBytes));
        }
        if (mode == McpStateMode.FILE) {
            writeAtomically(encoded);
        }
        state = candidate;
    }

    private void initialiseFileStore() {
        try {
            Files.createDirectories(stateDirectory);
            secureDirectory(stateDirectory);
            Path lockPath = stateDirectory.resolve(LOCK_FILE);
            lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            secureFile(lockPath);
            try {
                lock = lockChannel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new IllegalStateException("MCP_STATE_LOCKED", exception);
            }
            if (lock == null) {
                throw new IllegalStateException("MCP_STATE_LOCKED");
            }
            Path statePath = stateDirectory.resolve(STATE_FILE);
            if (Files.exists(statePath)) {
                byte[] bytes = Files.readAllBytes(statePath);
                if (bytes.length > maxStateBytes) {
                    throw new IllegalStateException("MCP_STATE_CAPACITY_EXCEEDED_AT_STARTUP");
                }
                CoordinationStateSchema.Migration migration = STATE_SCHEMA.migrate(mapper.readTree(bytes));
                state = mapper.treeToValue(migration.encodedState(), CoordinationStateDocument.class);
                validateLoadedState(state);
                if (migration.migrated()) {
                    replace(state);
                }
            }
        } catch (IllegalStateException exception) {
            closeAfterInitialisationFailure();
            throw exception;
        } catch (IOException | RuntimeException exception) {
            closeAfterInitialisationFailure();
            throw new IllegalStateException("MCP_STATE_CORRUPT", exception);
        }
    }

    private void validateLoadedState(CoordinationStateDocument loaded) {
        if (loaded.schemaVersion() != CoordinationStateSchema.CURRENT_VERSION || loaded.sessions() == null
            || loaded.workflows() == null || loaded.generatedFiles() == null
            || loaded.uploadCoordination() == null
            || openSessionCount(loaded, null) > maxOpenSessions) {
            throw new IllegalStateException("MCP_STATE_CORRUPT");
        }
        Map<PrincipalKey, Long> counts = new java.util.HashMap<>();
        loaded.sessions().values().stream()
            .filter(session -> session.state() == AgentSessionState.OPEN)
            .forEach(session -> counts.merge(session.principal(), 1L, Long::sum));
        if (counts.values().stream().anyMatch(count -> count > maxOpenSessionsPerPrincipal)) {
            throw new IllegalStateException("MCP_STATE_CAPACITY_EXCEEDED_AT_STARTUP");
        }
    }

    private void writeAtomically(byte[] encoded) {
        Path temporary = stateDirectory.resolve(STATE_FILE + ".tmp");
        Path target = stateDirectory.resolve(STATE_FILE);
        try {
            Files.write(temporary, encoded, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            secureFile(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException("MCP_STATE_ATOMIC_MOVE_UNSUPPORTED", exception);
            }
            secureFile(target);
        } catch (IOException exception) {
            throw new IllegalStateException("MCP_STATE_WRITE_FAILED", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The failed temporary is bounded and is reported by startup/RST inspection.
            }
        }
    }

    private static long openSessionCount(CoordinationStateDocument document, PrincipalKey principal) {
        return document.sessions().values().stream()
            .filter(session -> session.state() == AgentSessionState.OPEN)
            .filter(session -> principal == null || principal.equals(session.principal()))
            .count();
    }

    private static <T> void requirePresent(Map<String, T> values, String id, String code) {
        if (!values.containsKey(id)) {
            throw new ToolExecutionException(code, id);
        }
    }

    private static List<Map<String, Object>> copyFiles(List<Map<String, Object>> files) {
        List<Map<String, Object>> copy = new ArrayList<>(files.size());
        for (Map<String, Object> file : files) {
            copy.add(Map.copyOf(new LinkedHashMap<>(file)));
        }
        return List.copyOf(copy);
    }

    private static void secureDirectory(Path path) throws IOException {
        setPermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }

    private static void secureFile(Path path) throws IOException {
        setPermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static void setPermissions(Path path, EnumSet<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // The deployment gate verifies effective ACLs on non-POSIX platforms.
        }
    }

    private void closeAfterInitialisationFailure() {
        try {
            close();
        } catch (RuntimeException ignored) {
            // Preserve the original startup failure.
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
            if (lockChannel != null && lockChannel.isOpen()) {
                lockChannel.close();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("MCP_STATE_LOCK_RELEASE_FAILED", exception);
        }
    }

}
