package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.Patient;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "pfi.persistence.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryPatientRepository implements PatientRepository {
  private final ConcurrentMap<String, Patient> patientsById = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, String> idsByNormalizedReference = new ConcurrentHashMap<>();

  @Override
  public synchronized Patient save(Patient patient) {
    String normalized = normalize(patient.patientReference());
    if (idsByNormalizedReference.containsKey(normalized)) {
      throw new DuplicatePatientReferenceException(null);
    }
    patientsById.put(patient.id(), patient);
    idsByNormalizedReference.put(normalized, patient.id());
    return patient;
  }

  @Override
  public Optional<Patient> findById(String patientId) {
    return Optional.ofNullable(patientsById.get(patientId));
  }

  @Override
  public List<Patient> searchByReferencePrefix(String query, int limit) {
    String normalizedQuery = normalize(query);
    return patientsById.values().stream()
        .filter(patient -> normalize(patient.patientReference()).startsWith(normalizedQuery))
        .sorted(
            Comparator.comparing((Patient patient) -> normalize(patient.patientReference()))
                .thenComparing(Patient::id))
        .limit(limit)
        .toList();
  }

  @Override
  public synchronized Optional<Patient> updateReference(
      String patientId, String patientReference, Instant updatedAt) {
    Patient current = patientsById.get(patientId);
    if (current == null) return Optional.empty();
    String oldNormalized = normalize(current.patientReference());
    String newNormalized = normalize(patientReference);
    String owner = idsByNormalizedReference.get(newNormalized);
    if (owner != null && !owner.equals(patientId)) {
      throw new DuplicatePatientReferenceException(null);
    }
    Patient updated = new Patient(current.id(), patientReference, current.createdAt(), updatedAt);
    patientsById.put(patientId, updated);
    idsByNormalizedReference.remove(oldNormalized);
    idsByNormalizedReference.put(newNormalized, patientId);
    return Optional.of(updated);
  }

  private String normalize(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
