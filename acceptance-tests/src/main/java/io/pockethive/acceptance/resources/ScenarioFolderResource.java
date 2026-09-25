package io.pockethive.acceptance.resources;

import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.evidence.RunEvidence;
import java.io.IOException;

/**
 * Responsibility: own one new scenario folder and verify removal through the folder API.
 * Must not: delete existing folders, inspect container paths or implement recursive cleanup.
 * Contract: RESP-ACCEPTANCE-RESOURCES — docs/architecture/acceptance-tests.md#scenario-and-swarm-authorization-au-7au-12.
 */
public final class ScenarioFolderResource implements AutoCloseable {
  private final String path;
  private final ScenarioFolderApi observer;
  private final RunEvidence evidence;
  private AcquisitionState state = AcquisitionState.NOT_REQUESTED;
  public ScenarioFolderResource(String path, ScenarioFolderApi observer, RunEvidence evidence) {
    this.path = path; this.observer = observer; this.evidence = evidence;
  }
  public ApiResponse create(ScenarioFolderApi requester) throws IOException, InterruptedException {
    if (state != AcquisitionState.NOT_REQUESTED) throw new IllegalStateException("Folder create already attempted");
    if (observer.folders().contains(path)) throw new IllegalStateException("Test folder already exists: " + path);
    state = AcquisitionState.UNCONFIRMED;
    var response = requester.create(path);
    evidence.record("folder-create", response);
    if (response.status() == 204) state = AcquisitionState.ACQUIRED;
    else if (response.status() >= 400 && response.status() < 500 && response.status() != 408) state = AcquisitionState.REJECTED;
    return response;
  }
  public void delete(ScenarioFolderApi requester) throws IOException, InterruptedException {
    if (state == AcquisitionState.NOT_REQUESTED) throw new IllegalStateException("Folder not requested");
    var response = requester.delete(path);
    evidence.record("folder-delete", response);
    response.expect(204);
    var folders = observer.folders();
    evidence.record("folders-after-delete", folders);
    if (folders.contains(path)) throw new AssertionError("Deleted folder still present: " + path);
    state = AcquisitionState.RELEASED;
  }
  @Override public void close() throws IOException, InterruptedException {
    if (state == AcquisitionState.NOT_REQUESTED || state == AcquisitionState.RELEASED) return;
    var folders = observer.folders();
    evidence.record("folders-cleanup-read", folders);
    if (!folders.contains(path)) {
      if (state == AcquisitionState.UNCONFIRMED) throw new AssertionError("Unconfirmed folder create: " + path);
      state = AcquisitionState.RELEASED; return;
    }
    delete(observer);
  }
}
