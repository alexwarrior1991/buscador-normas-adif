package buscanormas;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Mapa "código del catálogo → PDF vigente en la web de ADIF".
 *
 * <p>En algunas redes la web de ADIF solo acepta navegadores, así que la aplicación no puede
 * buscar el PDF de una norma; pero el enlace de descarga directa sí funciona en el navegador. Este
 * mapa se genera donde sí hay acceso (comando {@code mapear}, que GitHub ejecuta cada semana), va
 * incluido en el jar y la aplicación baja la última versión de GitHub al arrancar.
 */
public final class MapaNormas {

    /** Una versión vigente de una norma y su PDF ({@code anexo} vacío si no tiene). */
    public record Entrada(String id, String codigo, String titulo, String edicion, String fecha,
            String anexo, String nombre) {
    }

    /** @param generado instante ISO-8601 (se comparan como texto para saber cuál es más reciente) */
    public record Datos(String generado, Map<String, List<Entrada>> normas) {
    }

    /** Busca las entradas de un código; la implementación real consulta la web de ADIF. */
    @FunctionalInterface
    interface Consulta {
        List<Entrada> entradas(String codigo) throws IOException;
    }

    static final String URL_GITHUB =
            "https://raw.githubusercontent.com/alexwarrior1991/buscador-normas-adif/main/mapa/mapa-normas.json";
    private static final String RECURSO = "/mapa-normas.json";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private volatile Datos datos = new Datos("", Map.of());

    public List<Entrada> buscar(String codigo) {
        return datos.normas().getOrDefault(codigo, List.of());
    }

    public String generado() {
        return datos.generado();
    }

    public int normas() {
        return datos.normas().size();
    }

    /** El más reciente entre el mapa incluido en el jar y el guardado en disco. */
    public void cargarLocal(Path fichero) {
        try (InputStream in = MapaNormas.class.getResourceAsStream(RECURSO)) {
            if (in != null) usarSiEsMasReciente(leer(in));
        } catch (IOException e) {
            System.err.println("Mapa de normas incluido ilegible: " + e.getMessage());
        }
        if (Files.exists(fichero)) {
            try (InputStream in = Files.newInputStream(fichero)) {
                usarSiEsMasReciente(leer(in));
            } catch (IOException e) {
                System.err.println("Mapa de normas en " + fichero + " ilegible: " + e.getMessage());
            }
        }
    }

    /** Baja el último mapa de GitHub; si es más reciente que el actual, lo guarda y lo usa. */
    public void actualizarDesde(String url, Path fichero) {
        try {
            HttpResponse<InputStream> r = ClienteHttp.cliente().build()
                    .send(ClienteHttp.peticion(url).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            Datos nuevo;
            try (InputStream in = r.body()) {
                nuevo = leer(in);
            }
            if (usarSiEsMasReciente(nuevo)) {
                escribir(nuevo, fichero);
                System.out.println("Mapa de normas actualizado desde GitHub (" + nuevo.generado() + ")");
            }
        } catch (IOException e) {
            System.err.println("No se pudo actualizar el mapa de normas desde GitHub: " + App.motivo(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized boolean usarSiEsMasReciente(Datos nuevo) {
        if (nuevo.normas() == null || nuevo.normas().isEmpty()) return false;
        if (nuevo.generado().compareTo(datos.generado()) <= 0) return false;
        datos = nuevo;
        return true;
    }

    static Datos leer(InputStream in) throws IOException {
        Datos d = JSON.readValue(in, Datos.class);
        return new Datos(d.generado() == null ? "" : d.generado(), d.normas() == null ? Map.of() : d.normas());
    }

    static void escribir(Datos d, Path fichero) throws IOException {
        Path absoluto = fichero.toAbsolutePath();
        Files.createDirectories(absoluto.getParent());
        Path temporal = Files.createTempFile(absoluto.getParent(), "mapa", ".part");
        try {
            JSON.writeValue(temporal.toFile(), d);
            Files.move(temporal, absoluto, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    /**
     * Consulta cada código del catálogo, con una pausa entre normas para no cargar la web de ADIF.
     * Los códigos que fallan se omiten (y se avisa): mejor sin entrada que con una equivocada.
     */
    static Datos generar(List<Norma> catalogo, Consulta consulta, long pausaMs, PrintStream log) {
        Map<String, List<Entrada>> normas = new LinkedHashMap<>();
        List<String> fallos = new ArrayList<>();
        int i = 0;
        for (Norma n : catalogo) {
            i++;
            if (normas.containsKey(n.codigo())) continue;
            try {
                List<Entrada> entradas = consulta.entradas(n.codigo());
                normas.put(n.codigo(), entradas);
                log.printf("[%d/%d] %s -> %s%n", i, catalogo.size(), n.codigo(),
                        entradas.isEmpty() ? "(no está en la web)" : entradas.size() == 1 ? entradas.get(0).codigo()
                                : entradas.size() + " documentos");
            } catch (IOException e) {
                fallos.add(n.codigo());
                log.printf("[%d/%d] %s -> ERROR %s%n", i, catalogo.size(), n.codigo(), App.motivo(e));
            }
            dormir(pausaMs);
        }
        if (!fallos.isEmpty()) log.println("Sin consultar por error: " + fallos);
        return new Datos(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), normas);
    }

    static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
