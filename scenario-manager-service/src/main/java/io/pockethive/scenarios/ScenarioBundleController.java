package io.pockethive.scenarios;

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
@RequestMapping("/scenario-bundles")
public class ScenarioBundleController {
    private static final Logger log = LoggerFactory.getLogger(ScenarioBundleController.class);

    private final ScenarioService service;

    public ScenarioBundleController(ScenarioService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/validate",
            consumes = "application/zip",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ScenarioService.BundleValidationResult validateBundle(@RequestBody byte[] body) throws IOException {
        int size = body != null ? body.length : 0;
        log.info("[REST] POST /scenario-bundles/validate contentType=application/zip size={}", size);
        ScenarioService.BundleValidationResult result = service.validateBundleZip(body);
        log.info("[REST] POST /scenario-bundles/validate -> status=200 ok={} findings={}",
                result.ok(), result.findings().size());
        return result;
    }

    @PostMapping(value = "/validate-existing", produces = MediaType.APPLICATION_JSON_VALUE)
    public ScenarioService.BundleValidationResult validateExistingBundle(@RequestParam("bundleKey") String bundleKey)
            throws IOException {
        log.info("[REST] POST /scenario-bundles/validate-existing bundleKey={}", bundleKey);
        try {
            ScenarioService.BundleValidationResult result = service.validateExistingBundle(bundleKey);
            log.info("[REST] POST /scenario-bundles/validate-existing -> status=200 ok={} findings={}",
                    result.ok(), result.findings().size());
            return result;
        } catch (IllegalArgumentException e) {
            log.warn("[REST] POST /scenario-bundles/validate-existing -> status=404 bundleKey={} {}",
                    bundleKey, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }
}
