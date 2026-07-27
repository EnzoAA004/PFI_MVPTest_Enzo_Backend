package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityStartupValidatorTest {
    @Test
    void nonProductionProfileNeverBlocksStartupRegardlessOfSecret() {
        MockEnvironment env = new MockEnvironment();
        assertDoesNotThrow(() -> new SecurityStartupValidator(env, "").validate());
        assertDoesNotThrow(() -> new SecurityStartupValidator(env, "pfi-demo-change-me-2026").validate());
    }

    @Test
    void productionWithBlankSecretFailsStartup() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "production");
        env.setActiveProfiles("production");
        assertThrows(IllegalStateException.class, () -> new SecurityStartupValidator(env, "").validate());
    }

    @Test
    void productionWithDemoDefaultSecretFailsStartup() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        assertThrows(IllegalStateException.class,
            () -> new SecurityStartupValidator(env, "pfi-demo-change-me-2026").validate());
    }

    @Test
    void productionWithWeakShortSecretFailsStartup() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        assertThrows(IllegalStateException.class, () -> new SecurityStartupValidator(env, "too-short").validate());
    }

    @Test
    void productionWithStrongSecretStartsCleanly() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        assertDoesNotThrow(() -> new SecurityStartupValidator(
            env, "a-sufficiently-long-random-production-secret-value-1234").validate());
    }

    @Test
    void prodAliasIsAlsoTreatedAsProduction() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new SecurityStartupValidator(env, "").validate());
    }
}
