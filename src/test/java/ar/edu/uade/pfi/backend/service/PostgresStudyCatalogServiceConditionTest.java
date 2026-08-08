package ar.edu.uade.pfi.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class PostgresStudyCatalogServiceConditionTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(LegacyCatalogConfiguration.class);

  @Test
  void legacyCatalogBeanIsNotCreatedUnlessExplicitlyEnabled() {
    contextRunner.run(
        context -> assertThat(context).doesNotHaveBean(PostgresStudyCatalogService.class));
  }

  @Configuration
  @Import(PostgresStudyCatalogService.class)
  static class LegacyCatalogConfiguration {}
}
