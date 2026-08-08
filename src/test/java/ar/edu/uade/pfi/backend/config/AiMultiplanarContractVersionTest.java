package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class AiMultiplanarContractVersionTest {
  @Test
  void absentValueDefaultsToV1() {
    assertEquals(
        AiMultiplanarContractVersion.V1, AiMultiplanarContractVersion.fromConfigValue(null));
    assertEquals(AiMultiplanarContractVersion.V1, AiMultiplanarContractVersion.fromConfigValue(""));
    assertEquals(
        AiMultiplanarContractVersion.V1, AiMultiplanarContractVersion.fromConfigValue("  "));
  }

  @Test
  void parsesKnownValuesCaseInsensitively() {
    assertEquals(
        AiMultiplanarContractVersion.V1, AiMultiplanarContractVersion.fromConfigValue("v1"));
    assertEquals(
        AiMultiplanarContractVersion.V1, AiMultiplanarContractVersion.fromConfigValue("V1"));
    assertEquals(
        AiMultiplanarContractVersion.V2, AiMultiplanarContractVersion.fromConfigValue("v2"));
    assertEquals(
        AiMultiplanarContractVersion.V2, AiMultiplanarContractVersion.fromConfigValue("V2"));
  }

  @Test
  void unknownValuePreventsStartupWithClearMessage() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> AiMultiplanarContractVersion.fromConfigValue("v3"));
    assertTrue(ex.getMessage().contains("multiplanar-contract-version"));
    assertTrue(ex.getMessage().contains("v1, v2"));
  }

  @Test
  void aiServicePropertiesResolvesRollbackToV1() {
    AiServiceProperties properties = new AiServiceProperties("http://ai-module", 60, "v2");
    assertEquals(AiMultiplanarContractVersion.V2, properties.resolvedMultiplanarContractVersion());

    AiServiceProperties rolledBack = new AiServiceProperties("http://ai-module", 60, "v1");
    assertEquals(AiMultiplanarContractVersion.V1, rolledBack.resolvedMultiplanarContractVersion());

    AiServiceProperties defaulted = new AiServiceProperties("http://ai-module", 60, null);
    assertEquals(AiMultiplanarContractVersion.V1, defaulted.resolvedMultiplanarContractVersion());
  }

  @Test
  void aiServicePropertiesRejectsUnknownValue() {
    AiServiceProperties properties = new AiServiceProperties("http://ai-module", 60, "v9");
    assertThrows(IllegalStateException.class, properties::resolvedMultiplanarContractVersion);
  }

  @Test
  void unknownConfigValuePreventsSpringContextStartup() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          AiServiceProperties.class, () -> new AiServiceProperties("http://ai-module", 60, "v3"));
      assertThrows(BeanCreationException.class, context::refresh);
    }
  }

  @Test
  void knownConfigValueStartsUpCleanly() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          AiServiceProperties.class, () -> new AiServiceProperties("http://ai-module", 60, "v2"));
      context.refresh();
      assertEquals(
          AiMultiplanarContractVersion.V2,
          context.getBean(AiServiceProperties.class).resolvedMultiplanarContractVersion());
    }
  }
}
