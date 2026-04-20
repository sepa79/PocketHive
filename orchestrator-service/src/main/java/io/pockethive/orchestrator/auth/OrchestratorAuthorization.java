package io.pockethive.orchestrator.auth;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHiveGrantChecks;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.orchestrator.app.ScenarioClient;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class OrchestratorAuthorization {
    private static final Set<String> READ_PERMISSIONS = Set.of(
        PocketHivePermissionIds.VIEW,
        PocketHivePermissionIds.RUN,
        PocketHivePermissionIds.ALL
    );
    private static final Set<String> RUN_PERMISSIONS = Set.of(
        PocketHivePermissionIds.RUN,
        PocketHivePermissionIds.ALL
    );
    private static final Set<String> MANAGE_PERMISSIONS = Set.of(PocketHivePermissionIds.ALL);

    public boolean isAllowed(AuthenticatedUserDto user, String method, String path) {
        if (user == null) {
            return true;
        }
        if (HttpMethod.OPTIONS.matches(method) || HttpMethod.HEAD.matches(method)) {
            return true;
        }
        if (HttpMethod.GET.matches(method)) {
            return PocketHiveGrantChecks.hasAnyPermission(user, READ_PERMISSIONS);
        }
        if (requiresRunPermission(method, path)) {
            return PocketHiveGrantChecks.hasAnyPermission(user, RUN_PERMISSIONS);
        }
        return PocketHiveGrantChecks.hasAnyPermission(user, MANAGE_PERMISSIONS);
    }

    public boolean canRead(AuthenticatedUserDto user, SwarmTemplateMetadata templateMetadata) {
        return hasPermissionInScope(user, READ_PERMISSIONS, templateMetadata);
    }

    public boolean canRun(AuthenticatedUserDto user, SwarmTemplateMetadata templateMetadata) {
        return hasPermissionInScope(user, RUN_PERMISSIONS, templateMetadata);
    }

    public boolean canManage(AuthenticatedUserDto user, SwarmTemplateMetadata templateMetadata) {
        return hasPermissionInScope(user, MANAGE_PERMISSIONS, templateMetadata);
    }

    public boolean canRun(AuthenticatedUserDto user, ScenarioClient.ScenarioTemplateDescriptor templateDescriptor) {
        return hasPermissionInScope(user, RUN_PERMISSIONS, templateDescriptor);
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
