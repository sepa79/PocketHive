package io.pockethive.tcpmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties
@SpringBootApplication
public class TcpMockServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TcpMockServerApplication.class, args);
    }
}
