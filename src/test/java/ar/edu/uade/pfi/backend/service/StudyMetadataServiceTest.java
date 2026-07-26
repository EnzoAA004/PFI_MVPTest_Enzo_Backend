package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.StudyMetadataDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StudyMetadataServiceTest {
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final StudyRunService service = new StudyRunService(repository);

    @Test
    void createsStudyWithSubjectRefAndKeepsNullWhenMetadataMissing() {
        Study withMetadata = service.upsertStudyMetadata("CASE-SPIDER-101", "created", new StudyMetadataDto(
            " SPIDER-101 ",
            LocalDate.parse("2026-07-26"),
            " MRI ",
            " RM lumbar sagital T2 ",
            "medium"
        ));
        assertEquals("SPIDER-101", withMetadata.subjectRef());
        assertEquals(LocalDate.parse("2026-07-26"), withMetadata.studyDate());
        assertEquals("MRI", withMetadata.modality());

        Study withoutMetadata = service.upsertStudyMetadata("CASE-NO-SUBJECT", "created", null);
        assertNull(withoutMetadata.subjectRef());
    }

    @Test
    void existingNullAcceptsAssignmentAndSameSubjectIsIdempotent() {
        Study created = service.upsertStudyMetadata("CASE-ASSIGN", "created", null);
        Study assigned = service.upsertStudyMetadata("CASE-ASSIGN", "created", new StudyMetadataDto("SPIDER-101", null, null, null, null));
        Study same = service.upsertStudyMetadata("CASE-ASSIGN", "created", new StudyMetadataDto("SPIDER-101", null, null, null, null));

        assertEquals(created.id(), assigned.id());
        assertEquals(assigned.updatedAt(), same.updatedAt());
        assertEquals("SPIDER-101", same.subjectRef());
    }

    @Test
    void differentSubjectRefConflictsAndRequestWithoutMetadataDoesNotClearExistingValues() {
        Study original = service.upsertStudyMetadata("CASE-CONFLICT", "created", new StudyMetadataDto(
            "SPIDER-101",
            LocalDate.parse("2026-07-26"),
            "MRI",
            "RM lumbar",
            "high"
        ));

        StudyMetadataException conflict = assertThrows(StudyMetadataException.class, () ->
            service.upsertStudyMetadata("CASE-CONFLICT", "created", new StudyMetadataDto("SPIDER-999", null, null, null, null))
        );
        assertEquals("SUBJECT_REFERENCE_CONFLICT", conflict.code());

        Study preserved = service.upsertStudyMetadata("CASE-CONFLICT", "created", null);
        assertEquals(original.subjectRef(), preserved.subjectRef());
        assertEquals(original.studyDate(), preserved.studyDate());
        assertEquals(original.description(), preserved.description());
    }

    @Test
    void invalidSubjectRefIsRejected() {
        StudyMetadataException ex = assertThrows(StudyMetadataException.class, () ->
            service.upsertStudyMetadata("CASE-INVALID", "created", new StudyMetadataDto("SPIDER 101", null, null, null, null))
        );
        assertEquals("INVALID_SUBJECT_REFERENCE", ex.code());
    }
}
