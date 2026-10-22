package dev.tessera.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Registers the Tessera view resolver (ordered before Thymeleaf's, falling through for unknown
 * names), the {@code Template} return-value handler, and the merged build-time
 * {@code TemplateRegistry}.
 *
 * <p>TODO(#7): beans.
 */
@AutoConfiguration
public class TesseraAutoConfiguration {}
