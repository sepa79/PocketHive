package io.pockethive.scenarios.test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import io.pockethive.scenarios.ScenarioManagerApplication;
import io.pockethive.scenarios.auth.ScenarioManagerAuthConfiguration;
import java.util.List;
import java.util.UUID;
import org.mockito.Mockito;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@ComponentScan(
    basePackages = {"io.pockethive.scenarios", "io.pockethive.capabilities"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {ScenarioManagerApplication.class, ScenarioManagerAuthConfiguration.class}))
public class ScenarioManagerTestApplication {
    @Bean
    public AuthServiceClient authServiceClient() {
        AuthServiceClient client = Mockito.mock(AuthServiceClient.class);
        when(client.resolve(anyString())).thenReturn(new AuthenticatedUserDto(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "test-admin",
            "Test Admin",
            true,
            AuthProvider.DEV,
            List.of(new AuthGrantDto(
                AuthProduct.POCKETHIVE,
                PocketHivePermissionIds.ALL,
                PocketHiveResourceTypes.DEPLOYMENT,
                "*"
            ))
        ));
        return client;
    }
}
