package io.pockethive.requesttemplates.files;

import io.pockethive.requesttemplates.TemplateDefinition;
import java.nio.file.Path;

/**
 * Responsibility: associate a parsed template with its source file.
 * Must not: validate templates or read files.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public record LoadedTemplate(TemplateDefinition definition, Path sourcePath) { }
