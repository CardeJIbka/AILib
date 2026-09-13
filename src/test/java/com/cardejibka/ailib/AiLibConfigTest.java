package com.cardejibka.ailib;

import com.cardejibka.ailib.config.AiLibConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}