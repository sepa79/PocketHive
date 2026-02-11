package io.pockethive.tools.sqltocsv.security;

import io.pockethive.tools.sqltocsv.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CredentialProvider Tests")
class CredentialProviderTest {

    @Test
    @DisplayName("Should resolve from first available source")
    void shouldResolveFromFirstAvailableSource() {
        CredentialSource source1 = mockSource("source1", Optional.empty());
        CredentialSource source2 = mockSource("source2", Optional.of("password123"));
        CredentialSource source3 = mockSource("source3", Optional.of("password456"));
        
        CredentialProvider provider = new CredentialProvider(List.of(source1, source2, source3));
        
        assertThat(provider.resolvePassword()).isEqualTo("password123");
    }

    @Test
    @DisplayName("Should throw ValidationException when no source provides password")
    void shouldThrowWhenNoSourceProvidesPassword() {
        CredentialSource source1 = mockSource("source1", Optional.empty());
        CredentialSource source2 = mockSource("source2", Optional.empty());
        
        CredentialProvider provider = new CredentialProvider(List.of(source1, source2));
        
        assertThatThrownBy(() -> provider.resolvePassword())
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("No password provided");
    }

    @Test
    @DisplayName("Should respect source priority order")
    void shouldRespectSourcePriorityOrder() {
        CredentialSource highPriority = mockSource("high", Optional.of("high-password"));
        CredentialSource lowPriority = mockSource("low", Optional.of("low-password"));
        
        CredentialProvider provider = new CredentialProvider(List.of(highPriority, lowPriority));
        
        assertThat(provider.resolvePassword()).isEqualTo("high-password");
    }

    @Test
    @DisplayName("Should handle empty source list")
    void shouldHandleEmptySourceList() {
        CredentialProvider provider = new CredentialProvider(List.of());
        
        assertThatThrownBy(() -> provider.resolvePassword())
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Should create immutable copy of sources")
    void shouldCreateImmutableCopyOfSources() {
        CredentialSource source = mockSource("test", Optional.of("password"));
        List<CredentialSource> mutableList = new java.util.ArrayList<>();
        mutableList.add(source);
        
        CredentialProvider provider = new CredentialProvider(mutableList);
        mutableList.clear(); // Modify original list
        
        assertThat(provider.resolvePassword()).isEqualTo("password");
    }

    private CredentialSource mockSource(String name, Optional<String> password) {
        return new CredentialSource() {
            @Override
            public Optional<String> resolve() {
                return password;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }
}
