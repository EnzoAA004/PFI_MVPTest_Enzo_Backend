package ar.edu.uade.pfi.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.service.ReviewStoreService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/**
 * Descarga de los objetos DICOM que genera el modulo de IA.
 *
 * <p>Son lo que permite abrir la segmentacion y las mediciones en 3D Slicer, OHIF o un PACS de
 * hospital. Hasta ahora la exportacion era csv, html o json: formatos que solo entiende este
 * producto.
 */
class AiDicomExportControllerTest {

  /** Un DICOM cualquiera: lo que importa acá es el transporte, no el contenido. */
  private static final byte[] DICOM = "DICM-fake-payload".getBytes();

  private MockMvc mockMvc(AiServiceOperations ai) {
    AiBackendService service =
        new AiBackendService(
            ai,
            Mockito.mock(ReviewStoreService.class),
            null,
            null,
            null,
            null,
            null,
            new InMemoryStudyRepository(),
            null,
            null,
            null);
    return MockMvcBuilders.standaloneSetup(
            new AiBackendController(service, Mockito.mock(RoleAuthorizationService.class)))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void laSegmentacionSeDescargaComoDicom() throws Exception {
    AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
    Mockito.when(ai.getRunSegmentation("abc123", "sagittal"))
        .thenReturn(new ResponseEntity<>(DICOM, HttpStatus.OK));

    mockMvc(ai)
        .perform(get("/api/ai/runs/abc123/sagittal/segmentation.dcm"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/dicom"))
        .andExpect(content().bytes(DICOM));
  }

  @Test
  void lasMedicionesSeDescarganComoDicom() throws Exception {
    AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
    Mockito.when(ai.getRunMeasurementReport("abc123", "axial"))
        .thenReturn(new ResponseEntity<>(DICOM, HttpStatus.OK));

    mockMvc(ai)
        .perform(get("/api/ai/runs/abc123/axial/measurements.sr.dcm"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/dicom"));
  }

  /**
   * El nombre del archivo lo arma el backend, no viene del upstream.
   *
   * <p>Un Content-Disposition copiado de afuera seria una via para que un nombre elegido por otro
   * sistema llegue al disco del usuario.
   */
  @Test
  void elNombreDelArchivoLoDecideElBackend() throws Exception {
    AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
    HttpHeaders upstreamHeaders = new HttpHeaders();
    upstreamHeaders.add(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"../../etc/passwd\"");
    Mockito.when(ai.getRunSegmentation(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(new ResponseEntity<>(DICOM, upstreamHeaders, HttpStatus.OK));

    mockMvc(ai)
        .perform(get("/api/ai/runs/abc123/sagittal/segmentation.dcm"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"abc123-sagittal-segmentation.dcm\""));
  }

  /**
   * No se cachea, a diferencia de los cortes.
   *
   * <p>El objeto se construye en el momento y una corrida revisada puede producir otro. Servir una
   * version vieja de un archivo que alguien va a abrir en otro visor es peor que volver a
   * generarlo.
   */
  @Test
  void laExportacionNoSeCachea() throws Exception {
    AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
    Mockito.when(ai.getRunSegmentation(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(new ResponseEntity<>(DICOM, HttpStatus.OK));

    mockMvc(ai)
        .perform(get("/api/ai/runs/abc123/sagittal/segmentation.dcm"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
  }

  @Test
  void unPlanoInvalidoSeRechazaSinLlamarAlModuloDeIa() throws Exception {
    AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);

    mockMvc(ai)
        .perform(get("/api/ai/runs/abc123/coronal/segmentation.dcm"))
        .andExpect(status().isBadRequest());

    Mockito.verifyNoInteractions(ai);
  }

  /** Estos identificadores van a un path: uno con barras podria salir a otra ruta. */
  @Test
  void unRunIdConCaracteresDeRutaSeRechaza() throws Exception {
    AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);

    mockMvc(ai)
        .perform(get("/api/ai/runs/ab%2F..%2Fhealth/sagittal/segmentation.dcm"))
        .andExpect(status().isBadRequest());

    Mockito.verifyNoInteractions(ai);
  }

  /**
   * El 409 llega tal cual y no se convierte en 502.
   *
   * <p>Significa que la corrida existe pero no reune las condiciones para exportar —entrada que no
   * es DICOM, mascara vacia, corrida sin mediciones—. Colapsarlo a "el modulo de IA fallo" haria
   * que el visor ofrezca reintentar algo que nunca va a andar.
   */
  @Test
  void unaCorridaQueNoSePuedeExportarDevuelve409() throws Exception {
    AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
    Mockito.when(ai.getRunSegmentation(Mockito.anyString(), Mockito.anyString()))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.CONFLICT, "AI Module segmentation export failed"));

    mockMvc(ai)
        .perform(get("/api/ai/runs/abc123/sagittal/segmentation.dcm"))
        .andExpect(status().isConflict());
  }

  /** Un cliente sin la operacion no revienta con 500. */
  @Test
  void unClienteSinLaOperacionNoProduceUn500() throws Exception {
    AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
    Mockito.when(ai.getRunSegmentation(Mockito.anyString(), Mockito.anyString()))
        .thenThrow(new UnsupportedOperationException("dicom_seg_export_unavailable"));

    mockMvc(ai)
        .perform(get("/api/ai/runs/abc123/sagittal/segmentation.dcm"))
        .andExpect(status().is5xxServerError());
  }
}
