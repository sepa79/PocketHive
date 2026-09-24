package io.pockethive.tcpmock.service;

import java.util.List;
import static org.mockito.Mockito.mock;

/** Explicit non-persistent fixture for tests unrelated to storage. */
final class TestMappingCatalogues {
    private TestMappingCatalogues() {}

    static MessageTypeRegistry fresh() {
        return new MessageTypeRegistry(mock(MappingPersistence.class), List::of);
    }
}
