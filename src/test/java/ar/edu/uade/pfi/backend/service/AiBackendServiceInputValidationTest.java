package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.StudyUploadResponseDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

class AiBackendServiceInputValidationTest {
  @Test
  void uploadInputRejectsOversizedFileBeforeCallingAiModule() {
    MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getOriginalFilename()).thenReturn("case-001.png");
    when(file.getSize()).thenReturn(AiBackendService.MAX_INPUT_UPLOAD_BYTES + 1);
    AiBackendService service =
        new AiBackendService(
            org.mockito.Mockito.mock(AiServiceOperations.class),
            org.mockito.Mockito.mock(ReviewStoreService.class));

    MaxUploadSizeExceededException ex =
        assertThrows(
            MaxUploadSizeExceededException.class,
            () -> service.uploadInput(file, "CASE-001", "sagittal"));

    assertEquals(AiBackendService.MAX_INPUT_UPLOAD_BYTES, ex.getMaxUploadSize());
  }

  @Test
  void uploadStudyRejectsNonZipSignatureBeforeCallingAiModule() {
    AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
    AiBackendService service =
        new AiBackendService(ai, org.mockito.Mockito.mock(ReviewStoreService.class));
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "study.zip", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1, 2, 3, 4});

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.uploadStudy(file, "CASE-001"));

    assertEquals(400, ex.getStatusCode().value());
    verifyNoInteractions(ai);
  }

  @Test
  void uploadStudyRejectsOversizedZipWithConfiguredLimitBeforeCallingAiModule() {
    MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getOriginalFilename()).thenReturn("study.zip");
    when(file.getContentType()).thenReturn(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    when(file.getSize()).thenReturn(11L);
    AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
    AiBackendService service = serviceWithStudyLimit(ai, 10L);

    MaxUploadSizeExceededException ex =
        assertThrows(
            MaxUploadSizeExceededException.class, () -> service.uploadStudy(file, "CASE-001"));

    assertEquals(10L, ex.getMaxUploadSize());
    verifyNoInteractions(ai);
  }

  @Test
  void uploadStudyPublishesOnlyAllowedFieldsFromAiModuleResponse() {
    AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "STUDY.ZIP", "application/zip", new byte[] {'P', 'K', 3, 4, 1});
    when(ai.uploadStudy(any(), eq("CASE-001")))
        .thenReturn(
            Map.ofEntries(
                Map.entry("caseId", "CASE-001"),
                Map.entry("studyId", "study-123"),
                Map.entry("sourcePath", "C:\\secret\\study.zip"),
                Map.entry("token", "secret-token"),
                Map.entry(
                    "seriesFound",
                    List.of(
                        Map.of(
                            "plane", "sagittal",
                            "description", "Sag T2",
                            "weighting", "T2",
                            "sliceCount", 24,
                            "seriesInstanceUid", "1.2.3.4",
                            "path", "/tmp/raw"))),
                Map.entry(
                    "sagittal",
                    Map.of(
                        "inputId", "input-sag",
                        "plane", "sagittal",
                        "format", "dcm",
                        "size", 42,
                        "description", "Sag T2",
                        "weighting", "T2",
                        "sliceCount", 24,
                        "sourcePath", "/tmp/raw")),
                Map.entry("traceId", "trace-ai-1")));
    AiBackendService service =
        new AiBackendService(ai, org.mockito.Mockito.mock(ReviewStoreService.class));

    StudyUploadResponseDto response = service.uploadStudy(file, "CASE-001");

    assertEquals("CASE-001", response.caseId());
    assertEquals("study-123", response.studyId());
    assertEquals("input-sag", response.sagittal().inputId());
    assertEquals(1, response.seriesFound().size());
    assertEquals("sagittal", response.seriesFound().get(0).plane());
    assertNull(response.axial());
  }

  private AiBackendService serviceWithStudyLimit(AiServiceOperations ai, long studyLimit) {
    return new AiBackendService(
        ai,
        org.mockito.Mockito.mock(ReviewStoreService.class),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        studyLimit);
  }
}
