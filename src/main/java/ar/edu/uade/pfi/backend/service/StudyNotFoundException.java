package ar.edu.uade.pfi.backend.service;

public class StudyNotFoundException extends RuntimeException {
    public StudyNotFoundException(String caseId) {
        super("Study not found: " + caseId);
    }
}
