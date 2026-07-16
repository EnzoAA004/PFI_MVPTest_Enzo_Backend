package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import java.util.List;
import java.util.Optional;

public interface StudyRepository {
    Study saveStudy(Study study);

    InputResource saveInput(InputResource input);

    StudyRun saveRun(StudyRun run);

    Optional<Study> findStudyByCaseId(String caseId);

    List<InputResource> findInputsByStudyId(String studyId);

    Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId);

    Optional<StudyRun> findRunByTraceId(String traceId);

    List<RunArtifact> findArtifactsByRunId(String studyRunId);
}
