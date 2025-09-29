package io.pockethive.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ProcessorApplicationConfigTest {

    @Test
    void controlPlaneWorkerSkipSelfSignalsDisabled() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties properties = factory.getObject();
        assertThat(properties)
            .as("processor service must consume control-plane signals directed at itself")
            .isNotNull();
        assertThat(properties.getProperty("pockethive.control-plane.worker.skip-self-signals"))
            .isEqualTo("false");
    }
}
