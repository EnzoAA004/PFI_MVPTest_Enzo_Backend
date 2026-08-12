package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.Patient;
import java.util.Optional;

public interface PatientRepository {
  Patient save(Patient patient);

  Optional<Patient> findById(String patientId);
}
