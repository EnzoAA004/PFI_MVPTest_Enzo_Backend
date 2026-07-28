package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityStartupValidatorTest {
    private static final String STRONG_SECRET = "a-sufficiently-long-random-production-secret-value-1234";
    private static final String EXACT_ORIGIN = "https://pfi-mvp-test-enzo-frontend.vercel.app";

    @Test
    void nonProductionProfileNeverBlocksStartupRegardlessOfConfig() {
        MockEnvironment env = new MockEnvironment();
        assertDoesNotThrow(() -> validator(env, "", false, true, true, "memory", "", "*", true).validate());
    }

    @Test
    void productionWithBlankSecretFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), "", true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithDemoDefaultSecretFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), "pfi-demo-change-me-2026", true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithWeakShortSecretFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), "too-short", true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void prodAliasIsAlsoTreatedAsProduction() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class,
            () -> validator(env, "", true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithAuthDisabledFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, false, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithDemoEnabledFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, true, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithExposeDevCodesTrueFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, false, true, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithExposeDevCodesFalseStartsCleanly() {
        assertDoesNotThrow(
            () -> validator(production(), STRONG_SECRET, true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithoutPostgresPersistenceFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, false, false, "memory", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithoutHttpsOriginFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, false, false, "postgres", "", "", false).validate());
    }

    @Test
    void productionWithLiteralWildcardOriginFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, false, false, "postgres", "*", "", false).validate());
    }

    @Test
    void productionWithPatternsButPreviewNotAllowedFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, false, false, "postgres", EXACT_ORIGIN, "https://*.vercel.app", false).validate());
    }

    @Test
    void productionWithPatternsAndPreviewExplicitlyAllowedStartsCleanly() {
        assertDoesNotThrow(() -> validator(
            production(), STRONG_SECRET, true, false, false, "postgres", EXACT_ORIGIN, "https://*.vercel.app", true).validate());
    }

    @Test
    void productionWithExactHttpsOriginAndStrongSecretStartsCleanly() {
        assertDoesNotThrow(
            () -> validator(production(), STRONG_SECRET, true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    private MockEnvironment production() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        return env;
    }

    private SecurityStartupValidator validator(
        MockEnvironment env,
        String jwtSecret,
        boolean authEnabled,
        boolean demoEnabled,
        boolean exposeDevCodes,
        String persistenceMode,
        String corsAllowedOrigins,
        String corsAllowedOriginPatterns,
        boolean allowPreviewPatterns
    ) {
        return new SecurityStartupValidator(
            env, jwtSecret, authEnabled, demoEnabled, exposeDevCodes,
            persistenceMode, corsAllowedOrigins, corsAllowedOriginPatterns, allowPreviewPatterns
        );
    }
}
