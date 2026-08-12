package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.Patient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PatientRepository {
  Patient save(Patient patient);

  Optional<Patient> findById(String patientId);

  List<Patient> searchByReferencePrefix(String query, int limit);

  Optional<Patient> updateReference(String patientId, String patientReference, Instant updatedAt);
}
