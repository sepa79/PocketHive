package io.pockethive.docker;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DockerRuntimeNamesTest {
    @Test
    void preservesExistingStackNameIndependentlyOfDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(DockerRuntimeNames.stackName("Hive-I" )).isEqualTo("ph-hive-i");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void rejectsMissingIdentity() {
        assertThatThrownBy(() -> DockerRuntimeNames.stackName(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerRuntimeNames.stackName(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
