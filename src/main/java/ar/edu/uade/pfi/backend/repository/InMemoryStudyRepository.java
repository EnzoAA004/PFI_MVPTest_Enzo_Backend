package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "pfi.persistence.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryStudyRepository implements StudyRepository {
    private final ConcurrentMap<String, Study> studiesById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> studyIdsByCaseId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, InputResource> inputsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StudyRun> runsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> runIdsByMultiplanarRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> runIdsByTraceId = new ConcurrentHashMap<>();

    @Override
    public Study saveStudy(Study study) {
        studiesById.put(study.id(), study);
        studyIdsByCaseId.put(study.caseId(), study.id());
        return study;
    }

    @Override
    public InputResource saveInput(InputResource input) {
        inputsById.put(input.id(), input);
        return input;
    }

    @Override
    public StudyRun saveRun(StudyRun run) {
        runsById.put(run.id(), run);
        runIdsByMultiplanarRunId.put(run.multiplanarRunId(), run.id());
        runIdsByTraceId.put(run.traceId(), run.id());
        return run;
    }

    @Override
    public Optional<Study> findStudyByCaseId(String caseId) {
        return Optional.ofNullable(studyIdsByCaseId.get(caseId)).map(studiesById::get);
    }

    @Override
    public List<InputResource> findInputsByStudyId(String studyId) {
        List<InputResource> inputs = new ArrayList<>();
        for (InputResource input : inputsById.values()) {
            if (input.studyId().equals(studyId)) inputs.add(input);
        }
        return inputs;
    }

    @Override
    public Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId) {
        return Optional.ofNullable(runIdsByMultiplanarRunId.get(multiplanarRunId)).map(runsById::get);
    }

    @Override
    public Optional<StudyRun> findRunByTraceId(String traceId) {
        return Optional.ofNullable(runIdsByTraceId.get(traceId)).map(runsById::get);
    }

    @Override
    public List<RunArtifact> findArtifactsByRunId(String studyRunId) {
        return runsById.values().stream()
            .filter(run -> run.id().equals(studyRunId))
            .findFirst()
            .map(StudyRun::artifacts)
            .orElse(List.of());
    }
}
