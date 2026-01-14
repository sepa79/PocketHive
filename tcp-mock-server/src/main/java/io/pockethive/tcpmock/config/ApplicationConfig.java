package io.pockethive.tcpmock.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "io.pockethive.tcpmock")
@EnableConfigurationProperties(TcpMockConfig.class)
public class ApplicationConfig {
}
