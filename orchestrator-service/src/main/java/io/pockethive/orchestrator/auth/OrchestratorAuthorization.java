package io.pockethive.orchestrator.auth;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHiveGrantChecks;
import io.pockethive.auth.contract.PocketHivePermissionSets;
import io.pockethive.orchestrator.app.ScenarioClient;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Responsibility: apply existing service request and resource authorization through shared grant checks.
 * Must not: own grant storage or duplicate shared permission combinations.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
@Component
public class OrchestratorAuthorization {
    public boolean isAllowed(AuthenticatedUserDto user, String method, String path) {
        if (user == null) {
            return true;
        }
        if (HttpMethod.OPTIONS.matches(method) || HttpMethod.HEAD.matches(method)) {
            return true;
        }
        if (HttpMethod.GET.matches(method)) {
            return PocketHiveGrantChecks.hasAnyPermission(user, PocketHivePermissionSets.READ);
        }
        if (requiresRunPermission(method, path)) {
            return PocketHiveGrantChecks.hasAnyPermission(user, PocketHivePermissionSets.RUN);
        }
        return PocketHiveGrantChecks.hasAnyPermission(user, PocketHivePermissionSets.MANAGE);
    }

    public boolean canRead(AuthenticatedUserDto user, SwarmTemplateMetadata templateMetadata) {
        return hasPermissionInScope(user, PocketHivePermissionSets.READ, templateMetadata);
    }

    public boolean canRead(AuthenticatedUserDto user, ScenarioClient.ScenarioTemplateDescriptor templateDescriptor) {
        return hasPermissionInScope(user, PocketHivePermissionSets.READ, templateDescriptor);
    }

    public boolean canRun(AuthenticatedUserDto user, SwarmTemplateMetadata templateMetadata) {
        return hasPermissionInScope(user, PocketHivePermissionSets.RUN, templateMetadata);
    }

    public boolean canManage(AuthenticatedUserDto user, SwarmTemplateMetadata templateMetadata) {
        return hasPermissionInScope(user, PocketHivePermissionSets.MANAGE, templateMetadata);
    }

    public boolean canRun(AuthenticatedUserDto user, ScenarioClient.ScenarioTemplateDescriptor templateDescriptor) {
        return hasPermissionInScope(user, PocketHivePermissionSets.RUN, templateDescriptor);
    }

    public boolean canManage(AuthenticatedUserDto user, ScenarioClient.ScenarioTemplateDescriptor templateDescriptor) {
        return hasPermissionInScope(user, PocketHivePermissionSets.MANAGE, templateDescriptor);
    }

    public boolean canReadPocketHive(AuthenticatedUserDto user) {
        if (user == null) {
            return true;
        }
        return PocketHiveGrantChecks.hasAnyPermission(user, PocketHivePermissionSets.READ);
    }

    public boolean canReadDeployment(AuthenticatedUserDto user) {
        if (user == null) {
            return true;
        }
        return PocketHiveGrantChecks.hasPermissionInScope(user, PocketHivePermissionSets.READ, null, null);
    }

    public boolean canManagePocketHive(AuthenticatedUserDto user) {
        if (user == null) {
            return true;
        }
        return PocketHiveGrantChecks.hasAnyPermission(user, PocketHivePermissionSets.MANAGE);
    }

    public boolean canManageDeployment(AuthenticatedUserDto user) {
        if (user == null) {
            return true;
        }
        return PocketHiveGrantChecks.hasPermissionInScope(user, PocketHivePermissionSets.MANAGE, null, null);
    }

    public String denialMessage(String method, String path) {
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return "PocketHive VIEW permission required";
        }
        if (requiresRunPermission(method, path)) {
            return "PocketHive RUN permission required";
        }
        return "PocketHive ALL permission required";
    }

    public String readDeniedMessage() {
        return "PocketHive VIEW permission required within matching scope";
    }

    public String runDeniedMessage() {
        return "PocketHive RUN permission required within matching scope";
    }

    public String manageDeniedMessage() {
        return "PocketHive ALL permission required within matching scope";
    }

    private boolean requiresRunPermission(String method, String path) {
        if (!HttpMethod.POST.matches(method)) {
            return false;
        }
        return path.matches("^/api/swarms/[^/]+/(create|start)$");
    }

    private boolean hasPermissionInScope(AuthenticatedUserDto user,
                                         Set<String> permissions,
                                         SwarmTemplateMetadata templateMetadata) {
        if (user == null) {
            return true;
        }
        return PocketHiveGrantChecks.hasPermissionInScope(
            user,
            permissions,
            templateMetadata == null ? null : templateMetadata.bundlePath(),
            templateMetadata == null ? null : templateMetadata.folderPath());
    }

    private boolean hasPermissionInScope(AuthenticatedUserDto user,
                                         Set<String> permissions,
                                         ScenarioClient.ScenarioTemplateDescriptor templateDescriptor) {
        if (user == null) {
            return true;
        }
        return PocketHiveGrantChecks.hasPermissionInScope(
            user,
            permissions,
            templateDescriptor == null ? null : templateDescriptor.bundlePath(),
            templateDescriptor == null ? null : templateDescriptor.folderPath());
    }
}
