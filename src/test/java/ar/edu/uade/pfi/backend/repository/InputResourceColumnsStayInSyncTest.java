package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The columns {@code findInputsByStudyId} selects must cover what {@code readInput} reads.
 *
 * <p>This exists because the two drifted apart and took the app down: BE-015 added six columns to
 * {@code InputResource} and to {@code readInput}, but not to the SELECT. Every read of a study's
 * inputs then threw {@code SQLException: column not found}, which the repository wraps as {@code
 * IllegalStateException} and the service turns into {@code DatabaseUnavailableException} — so the
 * browser saw a bare 503 on {@code /api/studies} and nothing pointed at a column list.
 *
 * <p>It reads the source rather than a database on purpose: the real coverage belongs to {@code
 * PostgresStudyRepositoryTest}, but that one needs a Testcontainers Postgres and is skipped
 * wherever the Docker daemon is not reachable — which is exactly the situation in which this
 * mistake shipped. This check runs anywhere.
 */
class InputResourceColumnsStayInSyncTest {

  private static final Path SOURCE =
      Path.of("src/main/java/ar/edu/uade/pfi/backend/repository/PostgresStudyRepository.java");

  @Test
  @DisplayName("readInput solo lee columnas que findInputsByStudyId trae")
  void selectedColumnsCoverEveryColumnReadBack() throws IOException {
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    Set<String> selected = selectedColumns(source);
    Set<String> read = columnsReadIn(methodBody(source, "private InputResource readInput"));

    assertTrue(selected.size() > 5, "no se pudo leer la lista de columnas del SELECT: " + selected);
    assertTrue(read.size() > 5, "no se pudieron leer las columnas de readInput: " + read);

    Set<String> missing = new LinkedHashSet<>(read);
    missing.removeAll(selected);
    assertTrue(
        missing.isEmpty(),
        "readInput lee columnas que el SELECT no trae: "
            + missing
            + ". Agregarlas a findInputsByStudyId, o la lectura de inputs falla con un 503 sin causa visible.");
  }

  /**
   * Columnas del SELECT de {@code findInputsByStudyId}, hasta su FROM.
   *
   * <p>El bloque no puede contener otro {@code FROM}: sin esa restricción la búsqueda arranca en un
   * SELECT anterior del archivo y arrastra hasta acá todo lo que haya en el medio, de modo que la
   * lista de columnas queda contaminada con texto suelto y el test informa de menos.
   */
  private Set<String> selectedColumns(String source) {
    Matcher matcher =
        Pattern.compile("SELECT((?:(?!FROM)[\\s\\S])+?)FROM domain_input_resources")
            .matcher(source);
    Set<String> columns = new LinkedHashSet<>();
    if (!matcher.find()) return columns;
    for (String column : matcher.group(1).split(",")) {
      String name = column.trim();
      if (!name.isEmpty()) columns.add(name);
    }
    return columns;
  }

  /**
   * Cuerpo del método, delimitado contando llaves balanceadas desde su firma.
   *
   * <p>Antes buscaba una llave de cierre precedida por cuatro espacios, es decir asumía esa
   * indentación. Con un formateador que indenta a dos —google-java-format, el que trae el build—
   * ese delimitador no aparece, el método se extiende hasta el final del archivo y {@link
   * #columnsReadIn} termina levantando las columnas de {@code readRun}, que es el método siguiente.
   * El test fallaba entonces con una lista de columnas de runs ({@code reviewed_at}, {@code
   * trace_id}, {@code multiplanar_run_id}…) señalando un bug de SQL que no existía. Las llaves no
   * dependen de cómo esté indentado el código.
   *
   * <p>Si el cuerpo no se puede delimitar, devuelve vacío: el llamador ya exige un mínimo de
   * columnas leídas y falla con un mensaje que dice justamente eso, en vez de dar por buena una
   * comparación contra nada.
   */
  private String methodBody(String source, String signature) {
    int start = source.indexOf(signature);
    if (start < 0) return "";
    int open = source.indexOf('{', start);
    if (open < 0) return "";

    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      char current = source.charAt(i);
      if (current == '{') {
        depth++;
      } else if (current == '}') {
        depth--;
        if (depth == 0) return source.substring(open, i + 1);
      }
    }
    return "";
  }

  /** Nombres pedidos al ResultSet: {@code rs.getString("plane")} y compañía. */
  private Set<String> columnsReadIn(String body) {
    Matcher matcher = Pattern.compile("rs\\.get\\w+\\(\\s*\"([a-z_]+)\"").matcher(body);
    Set<String> columns = new LinkedHashSet<>();
    while (matcher.find()) columns.add(matcher.group(1));
    return columns;
  }
}
