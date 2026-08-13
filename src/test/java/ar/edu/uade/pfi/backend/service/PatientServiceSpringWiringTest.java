package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import ar.edu.uade.pfi.backend.repository.PatientRepository;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PatientServiceSpringWiringTest {

  @Test
  void applicationContextWiresTheProductionConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(PatientRepository.class, () -> mock(PatientRepository.class));
      context.registerBean(StudyRepository.class, () -> mock(StudyRepository.class));
      context.registerBean(AuditService.class, () -> mock(AuditService.class));
      context.register(PatientService.class);

      context.refresh();

      assertNotNull(context.getBean(PatientService.class));
    }
  }
}
