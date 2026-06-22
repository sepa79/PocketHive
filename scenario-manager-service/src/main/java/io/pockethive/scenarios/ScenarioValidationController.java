package io.pockethive.scenarios;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import io.pockethive.scenarios.auth.ScenarioManagerCurrentUserHolder;
import io.pockethive.scenarios.validation.BundleValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@RestController
@RequestMapping("/validation/scenario-bundles")
public class ScenarioValidationController {
    private static final Logger log = LoggerFactory.getLogger(ScenarioValidationController.class);

    private final ScenarioService service;
    private final ScenarioManagerAuthorization authorization;

    public ScenarioValidationController(ScenarioService service, ScenarioManagerAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping(consumes = "application/zip", produces = MediaType.APPLICATION_JSON_VALUE)
    public BundleValidationResult validateBundle(@RequestBody byte[] body) throws IOException {
        int size = body != null ? body.length : 0;
        log.info("[REST] POST /validation/scenario-bundles contentType=application/zip size={}", size);
        requireManagePocketHive();
        BundleValidationResult result = service.validateBundleZip(body);
        log.info("[REST] POST /validation/scenario-bundles -> status=200 ok={} findings={}",
                result.ok(), result.findings().size());
        return result;
    }

    @PostMapping(value = "/existing", produces = MediaType.APPLICATION_JSON_VALUE)
    public BundleValidationResult validateExistingBundle(@RequestParam("bundleKey") String bundleKey)
            throws IOException {
        log.info("[REST] POST /validation/scenario-bundles/existing bundleKey={}", bundleKey);
        requireManagePocketHive();
        try {
            BundleValidationResult result = service.validateExistingBundle(bundleKey);
            log.info("[REST] POST /validation/scenario-bundles/existing -> status=200 ok={} findings={}",
                    result.ok(), result.findings().size());
            return result;
        } catch (IllegalArgumentException e) {
            log.warn("[REST] POST /validation/scenario-bundles/existing -> status=404 bundleKey={} {}",
                    bundleKey, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    private void requireManagePocketHive() {
        AuthenticatedUserDto user = ScenarioManagerCurrentUserHolder.get();
        if (!authorization.canManagePocketHive(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, authorization.manageDeniedMessage());
        }
    }
}
