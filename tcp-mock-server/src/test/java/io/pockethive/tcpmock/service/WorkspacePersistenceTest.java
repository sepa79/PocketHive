package io.pockethive.tcpmock.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePersistenceTest {
  @TempDir Path root;

  private WorkspaceService open() {
    return new WorkspaceService(new WorkspaceFileStore(root));
  }

  @Test
  void restartRetainsIdentitiesOwnersOrderRenamesDeletionAndDefaultPolicy() {
    var service = open();
    var first = service.create("One", false, "NATIVE:fixture");
    var second = service.create("Two", true, "NATIVE:fixture");
    service.update(first.id(), "Renamed", true);
    service.update("default", "My default", false);
    service.delete(second.id());
    var expected = service.findAll();
    var restarted = open();
    assertEquals(expected, restarted.findAll());
    assertEquals(first.owner(), restarted.findAll().getLast().owner());
    assertThrows(IllegalStateException.class, () -> restarted.delete("default"));
    restarted.delete(first.id());
    assertEquals(restarted.findAll(), open().findAll());
    assertEquals(1, open().findAll().size());
  }

  @Test
  void failedReplacementDoesNotPublishCandidateAndFencesFurtherMutation() throws Exception {
    var service = open();
    var entry = service.create("Keep", false, "NATIVE:fixture");
    var expected = service.findAll();
    Path snapshot = root.resolve("workspace-catalogue.json");
    byte[] bytes = Files.readAllBytes(snapshot);
    Files.move(snapshot, root.resolve("previous.json"));
    Files.createDirectory(snapshot);
    Files.writeString(snapshot.resolve("block-replacement"), "fixture");
    assertThrows(UncheckedIOException.class, () -> service.update(entry.id(), "Lost", true));
    assertEquals(expected, service.findAll());
    assertArrayEquals(bytes, Files.readAllBytes(root.resolve("previous.json")));
    assertThrows(
        IllegalStateException.class, () -> service.create("Later", false, "NATIVE:fixture"));
    assertThrows(IllegalStateException.class, () -> service.delete(entry.id()));
    assertEquals(expected, service.findAll());
  }

  @Test
  void invalidSnapshotsFailStartupWithoutReplacingEvidence() throws Exception {
    open();
    Path snapshot = root.resolve("workspace-catalogue.json");
    String valid = Files.readString(snapshot);
    for (String invalid :
        new String[] {
          "null",
          "{broken",
          "{}",
          "[]",
          valid.replace("\"version\" : 1", "\"version\" : 2"),
          valid.replace("\"defaultWorkspace\" : true", "\"defaultWorkspace\" : false"),
          valid.replace("\"deletable\" : false", "\"deletable\" : true"),
          valid.replace("\"id\" : \"default\"", "\"id\" : \"another\""),
          valid + "{}"
        }) {
      Files.writeString(snapshot, invalid);
      assertThrows(RuntimeException.class, this::open, invalid);
      assertEquals(invalid, Files.readString(snapshot));
    }
  }

  @Test
  void duplicateIdentitiesAndMissingDefaultAreRejected() throws Exception {
    open();
    Path snapshot = root.resolve("workspace-catalogue.json");
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var document =
        (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(snapshot.toFile());
    var entries = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("workspaces");
    entries.add(entries.get(0).deepCopy());
    Files.writeString(snapshot, document.toString());
    assertThrows(IllegalStateException.class, this::open);
    entries.removeAll();
    Files.writeString(snapshot, document.toString());
    assertThrows(IllegalStateException.class, this::open);
  }

  @Test
  void inaccessibleDataDirectoryCannotBecomeAnEmptyCatalogue() throws Exception {
    Path blocked = root.resolve("blocked");
    Files.writeString(blocked, "not a directory");
    assertThrows(
        UncheckedIOException.class, () -> new WorkspaceService(new WorkspaceFileStore(blocked)));
  }
}
