package com.cardejibka.ailib;

import com.cardejibka.ailib.config.AiLibConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiLibConfigTest {

    private AiLibConfig configWithDomains(String... domains) {
        AiLibConfig config = new AiLibConfig();
        config.allowedModelDownloadDomains = List.of(domains);
        return config;
    }

    @Test
    void exactDomainMatchIsAllowed() {
        assertTrue(configWithDomains("huggingface.co").isDomainAllowed("huggingface.co"));
    }

    @Test
    void subdomainIsAllowed() {
        assertTrue(configWithDomains("huggingface.co").isDomainAllowed("cdn-lfs.huggingface.co"));
    }

    @Test
    void unrelatedDomainIsRejected() {
        assertFalse(configWithDomains("huggingface.co").isDomainAllowed("evil-download-site.com"));
    }

    @Test
    void similarButDifferentSuffixIsRejected() {
        assertFalse(configWithDomains("huggingface.co").isDomainAllowed("huggingface.co.evil.com"));
    }

    @Test
    void nullHostIsRejected() {
        assertFalse(configWithDomains("huggingface.co").isDomainAllowed(null));
    }

    @Test
    void caseInsensitive() {
        assertTrue(configWithDomains("HuggingFace.CO").isDomainAllowed("HUGGINGFACE.co"));
    }

    @Test
    void nullDomainListDoesNotThrowAndIsRepairedBySanitize() {
        AiLibConfig config = new AiLibConfig();
        config.allowedModelDownloadDomains = null;
        assertFalse(config.isDomainAllowed("huggingface.co"));
        config.sanitize();
        assertTrue(config.isDomainAllowed("huggingface.co"));
    }

    @Test
    void sanitizeClampsBadValues() {
        AiLibConfig config = new AiLibConfig();
        config.maxParallelDownloads = 0;
        config.llmTimeoutSeconds = -5;
        config.llmExtraArgs = null;
        config.sanitize();
        assertEquals(1, config.maxParallelDownloads);
        assertEquals(1, config.llmTimeoutSeconds);
        assertTrue(config.llmExtraArgs.isEmpty());
    }
}
