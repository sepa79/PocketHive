package io.pockethive.scenarios;

import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceSelectors;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

final class AuthTestUsers {

    static final String TEST_BEARER = "Bearer test-token";

    private AuthTestUsers() {
    }

    static AuthenticatedUserDto admin() {
        return userWith(PocketHivePermissionIds.ALL);
    }

    static AuthenticatedUserDto viewer() {
        return userWith(PocketHivePermissionIds.VIEW);
    }

    static AuthenticatedUserDto userWith(String permission) {
        return userWith(permission, PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL);
    }

    static AuthenticatedUserDto userWith(String permission, String resourceType, String resourceSelector) {
        return new AuthenticatedUserDto(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "local-user",
            "Local User",
            true,
            AuthProvider.DEV,
            List.of(new AuthGrantDto(
                AuthProduct.POCKETHIVE,
                permission,
                resourceType,
                resourceSelector
            ))
        );
    }

    static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, TEST_BEARER);
    }
}
