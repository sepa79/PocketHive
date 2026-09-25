package io.pockethive.tcpmock.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceServiceTest {
  @TempDir Path root;

  @Test
  void defaultPolicySurvivesRenameAndCannotBeDeleted() {
    var service = new WorkspaceService(new WorkspaceFileStore(root));
    var initial = service.findAll().getFirst();
    assertTrue(initial.defaultWorkspace());
    assertFalse(initial.deletable());
    var renamed = service.update(initial.id(), "Renamed", true);
    assertEquals(initial.id(), renamed.id());
    assertEquals(initial.owner(), renamed.owner());
    assertTrue(renamed.defaultWorkspace());
    assertFalse(renamed.deletable());
    assertThrows(IllegalStateException.class, () -> service.delete(initial.id()));
    assertEquals(1, service.findAll().size());
  }

  @Test
  void concurrentCreationHasUniqueStableIdsAndNoLostEntries() {
    var service = new WorkspaceService(new WorkspaceFileStore(root));
    var created =
        IntStream.range(0, 500)
            .parallel()
            .mapToObj(i -> service.create("Workspace " + i, false, "NATIVE:fixture"))
            .toList();
    assertEquals(500, created.stream().map(w -> w.id()).distinct().count());
    assertEquals(501, service.findAll().size());
    for (var workspace : created) {
      assertFalse(workspace.defaultWorkspace());
      assertTrue(workspace.deletable());
      service.delete(workspace.id());
    }
    assertEquals(1, service.findAll().size());
  }

  @Test
  void unknownMutationsFailWithoutUpsert() {
    var service = new WorkspaceService(new WorkspaceFileStore(root));
    assertThrows(NoSuchElementException.class, () -> service.update("missing", "name", false));
    assertThrows(NoSuchElementException.class, () -> service.delete("missing"));
    assertEquals(1, service.findAll().size());
  }

  @Test
  void invalidNamesLeaveCatalogueUnchangedAndSnapshotsRemainImmutable() {
    var service = new WorkspaceService(new WorkspaceFileStore(root));
    var created = service.create("  Original  ", false, "NATIVE:fixture");
    var snapshot = service.findAll();
    assertEquals("Original", created.name());
    for (String invalid : new String[] {null, " ", "x".repeat(129)}) {
      assertThrows(
          IllegalArgumentException.class, () -> service.create(invalid, false, "NATIVE:fixture"));
      assertThrows(
          IllegalArgumentException.class, () -> service.update(created.id(), invalid, false));
    }
    assertEquals(snapshot, service.findAll());
    service.update(created.id(), "Changed", true);
    assertEquals("Original", snapshot.getLast().name());
    assertThrows(UnsupportedOperationException.class, snapshot::clear);
  }
}
