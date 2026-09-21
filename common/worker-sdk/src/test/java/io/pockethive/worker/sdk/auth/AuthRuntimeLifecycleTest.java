package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthRuntimeLifecycleTest {
    @Test
    void closesOwnedStoreAndClientExactlyOnce() {
        TokenStore store = mock(TokenStore.class);
        HttpClient client = mock(HttpClient.class);
        AuthRuntimeResources resources = new AuthRuntimeResources(store, client);

        resources.close();
        resources.close();

        verify(store, times(1)).close();
        verify(client, times(1)).close();
    }

    @Test
    void injectedRuntimeLeavesBorrowedResourcesOpen() {
        TokenStore store = mock(TokenStore.class);
        HttpClient client = mock(HttpClient.class);
        try (AuthRuntime runtime = new AuthRuntime(Map.of(), Map.of(), store, (template, context) -> template, client)) {
            assertThat(runtime.active()).isFalse();
        }
        verifyNoInteractions(store, client);
    }

    @Test
    void runtimeSetupFailureAlsoLeavesBorrowedResourcesOpen() {
        TokenStore store = mock(TokenStore.class);
        HttpClient client = mock(HttpClient.class);
        assertThatThrownBy(() -> new AuthRuntime(null, Map.of(), store, (template, context) -> template, client))
            .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(store, client);
    }

    @Test
    void closesBothResourcesAndPreservesBothCleanupFailures() {
        TokenStore store = mock(TokenStore.class);
        HttpClient client = mock(HttpClient.class);
        RuntimeException storeFailure = new IllegalStateException("store close failed");
        RuntimeException clientFailure = new IllegalStateException("client close failed");
        doThrow(storeFailure).when(store).close();
        doThrow(clientFailure).when(client).close();
        AuthRuntimeResources resources = new AuthRuntimeResources(store, client);

        assertThatThrownBy(resources::close).isSameAs(storeFailure);
        assertThat(storeFailure.getSuppressed()).containsExactly(clientFailure);
        verify(store).close();
        verify(client).close();
    }

    @Test
    void propagatesClientCleanupFailureWithoutStoreFailure() {
        HttpClient client = mock(HttpClient.class);
        RuntimeException failure = new IllegalStateException("client close failed");
        doThrow(failure).when(client).close();
        AuthRuntimeResources resources = new AuthRuntimeResources(null, client);
        assertThatThrownBy(resources::close).isSameAs(failure);
    }

    @Test
    void releasesResourcesWithoutInterruptingCleanupThenRestoresInterruption() {
        TokenStore store = mock(TokenStore.class);
        HttpClient client = mock(HttpClient.class);
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            Thread.currentThread().interrupt();
            return null;
        }).when(store).close();
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return null;
        }).when(client).close();
        AuthRuntimeResources resources = new AuthRuntimeResources(store, client);
        Thread.currentThread().interrupt();
        try {
            resources.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(store).close();
            verify(client).close();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void initializationFailureRetainsCleanupFailureAsSuppressed() {
        TokenStore store = mock(TokenStore.class);
        HttpClient client = mock(HttpClient.class);
        RuntimeException original = new IllegalStateException("initialization failed");
        RuntimeException cleanup = new IllegalStateException("store close failed");
        doThrow(cleanup).when(store).close();
        new AuthRuntimeResources(store, client).closeAfterFailure(original);

        assertThat(original.getSuppressed()).containsExactly(cleanup);
        verify(client).close();
    }

    @Test
    void runtimeWithoutTokenStoreTerminatesItsRealHttpClient() {
        AuthRuntimeResources resources = AuthRuntimeResources.withoutTokenStore();
        assertThat(resources.tokenStore()).isNull();
        assertThat(resources.httpClient().isTerminated()).isFalse();
        resources.close();
        assertThat(resources.httpClient().isTerminated()).isTrue();
    }
}
