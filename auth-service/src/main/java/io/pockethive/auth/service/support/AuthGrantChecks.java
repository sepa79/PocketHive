package io.pockethive.auth.service.support;

import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.AuthServicePermissionIds;
import io.pockethive.auth.contract.AuthServiceResourceTypes;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class AuthGrantChecks {
    public boolean isAuthAdmin(AuthenticatedUserDto user) {
        return user != null && user.grants().stream().anyMatch(this::isAuthAdminGrant);
    }

    private boolean isAuthAdminGrant(AuthGrantDto grant) {
        return grant.product() == AuthProduct.AUTH_SERVICE
            && Objects.equals(grant.permission(), AuthServicePermissionIds.ADMIN)
            && Objects.equals(grant.resourceType(), AuthServiceResourceTypes.GLOBAL)
            && Objects.equals(grant.resourceSelector(), "*");
    }
}
