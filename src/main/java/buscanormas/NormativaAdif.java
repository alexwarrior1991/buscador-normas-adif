package buscanormas;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cliente de la web oficial de normativa técnica de ADIF (normativatecnica.adif.es/ntw).
 *
 * <p>Usa la misma API JSON interna que la propia web al buscar y al pulsar "Ver documento":
 * <ol>
 *   <li>{@code GET  /views/busqueda/body-busqueda.jsp}: cookie de sesión y token CSRF;</li>
 *   <li>{@code POST /action/BusquedaController/getDocumentos}: versiones de una norma por código;</li>
 *   <li>{@code GET  /action/DocumentacionController/getDocumentosAnexos}: ficheros de esa versión;</li>
 *   <li>{@code GET  /action/DocumentacionController/descargarDocumentoAnexo}: el PDF.</li>
 * </ol>
 * Si ADIF cambia su web esto puede dejar de funcionar; el buscador no se ve afectado.
 */
public final class NormativaAdif {

    /** Una versión de una norma en la web oficial. */
    public record Documento(String id, String codigo, String titulo, String edicion, String fecha, String estado) {
    }

    /** Un fichero de una versión (normalmente un único PDF). */
    public record Anexo(String id, String nombre, String formato) {
    }

    public static final String WEB = "https://normativatecnica.adif.es/ntw";
    /** Buscador de la web oficial, para cuando hay que buscar desde el navegador del usuario. */
    public static final String BUSCADOR = WEB + "/views/busqueda/body-busqueda.jsp";
    private static final Pattern TOKEN = Pattern.compile("tokenCsrf = \"([^\"]+)\"");
    private static final Pattern ALTERNATIVO = Pattern.compile("\\s*\\(.*\\)");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = ClienteHttp.cliente().cookieHandler(new CookieManager()).build();
    private final Map<String, List<Documento>> cache = new ConcurrentHashMap<>();
    private volatile String token;

    /** Olvida lo consultado (al actualizar el catálogo puede haber ediciones nuevas). */
    public void olvidar() {
        cache.clear();
    }

    /**
     * Versiones vigentes que corresponden a un código del catálogo. Normalmente una; varias en
     * colecciones como "PAV" (planos tipo de vía); ninguna si la web no la tiene.
     */
    public List<Documento> vigentes(String codigoCatalogo) throws IOException {
        List<Documento> enCache = cache.get(codigoCatalogo);
        if (enCache != null) return enCache;

        String base = ALTERNATIVO.matcher(codigoCatalogo).replaceAll("").strip();
        List<Documento> elegidos = elegir(codigoCatalogo, buscar(base));
        String prefijo = base.split("\\s+")[0];
        if (elegidos.isEmpty() && !prefijo.equals(base)) {
            // "N.F.I. AN DENES 001": el catálogo parte palabras; se busca por el prefijo y se compara sin signos.
            elegidos = elegir(codigoCatalogo, buscar(prefijo));
        }
        cache.put(codigoCatalogo, elegidos);
        return elegidos;
    }

    /**
     * De todas las versiones que devuelve la web, las vigentes de ese código: primero las que
     * coinciden exactamente (admitiendo sufijo de versión: "NAV 3-3-2.1_ED4"); si no hay, las que
     * empiezan por él (colecciones como "PAV 01_01.01.000_ED2M1").
     */
    static List<Documento> elegir(String codigoCatalogo, List<Documento> documentos) {
        String clave = Indice.claveCodigo(ALTERNATIVO.matcher(codigoCatalogo).replaceAll(""));
        List<Documento> vigentes = documentos.stream()
                .filter(d -> d.estado().equalsIgnoreCase("Vigente"))
                .sorted(Comparator.comparing(Documento::codigo))
                .toList();
        List<Documento> exactos = vigentes.stream()
                .filter(d -> Indice.claveCodigo(d.codigo()).equals(clave)
                        || Indice.claveCodigo(d.codigo().replaceFirst("_.*$", "")).equals(clave))
                .toList();
        if (!exactos.isEmpty() || clave.isEmpty()) return exactos;
        return vigentes.stream().filter(d -> Indice.claveCodigo(d.codigo()).startsWith(clave)).toList();
    }

    /**
     * Comprueba que Java puede usar la web (en algunas redes la rechaza con HTTP 403 aunque el
     * navegador sí pueda). Lanza la excepción con el motivo si no.
     */
    public void comprobarAcceso() throws IOException {
        token();
    }

    /** Descarga directa de un PDF: funciona sin sesión, así que el navegador puede abrirla tal cual. */
    public static String urlDescarga(String idAnexo) {
        return WEB + "/action/DocumentacionController/descargarDocumentoAnexo?idDoc="
                + URLEncoder.encode(idAnexo, StandardCharsets.UTF_8);
    }

    /** El PDF de una versión, o null si no tiene. */
    public Anexo pdfDe(String idDocumento) throws IOException {
        String json = get(WEB + "/action/DocumentacionController/getDocumentosAnexos?idDocPadre="
                + URLEncoder.encode(idDocumento, StandardCharsets.UTF_8));
        return leerAnexos(json).stream().filter(a -> a.formato().equalsIgnoreCase("pdf")).findFirst().orElse(null);
    }

    /** Descarga a un temporal y solo lo deja en {@code destino} si es realmente un PDF. */
    public void descargar(String idAnexo, Path destino) throws IOException {
        Files.createDirectories(destino.toAbsolutePath().getParent());
        Path temporal = Files.createTempFile(destino.toAbsolutePath().getParent(), "norma", ".part");
        try {
            HttpResponse<Path> r = enviar(ClienteHttp.peticion(urlDescarga(idAnexo)).build(),
                    HttpResponse.BodyHandlers.ofFile(temporal));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " al descargar la norma");
            try (InputStream in = Files.newInputStream(temporal)) {
                if (!new String(in.readNBytes(5), StandardCharsets.US_ASCII).startsWith("%PDF")) {
                    throw new IOException("La web de ADIF no ha devuelto un PDF");
                }
            }
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    /** Búsqueda por código en la web; con la sesión caducada se renueva y se reintenta una vez. */
    private List<Documento> buscar(String codigo) throws IOException {
        String cuerpo = JSON.writeValueAsString(List.of(Map.of("name", "cdgo_prncpl", "value", codigo)));
        for (int intento = 0; ; intento++) {
            HttpResponse<String> r = enviar(ClienteHttp.peticion(WEB + "/action/BusquedaController/getDocumentos")
                    .header("Content-Type", "application/json")
                    .header("X-CSRF-TOKEN", token())
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200 && r.body().stripLeading().startsWith("{")) return leerDocumentos(r.body());
            token = null;
            if (intento == 1) throw new IOException("La web de ADIF no responde a la búsqueda (HTTP " + r.statusCode() + ")");
        }
    }

    private synchronized String token() throws IOException {
        if (token == null) {
            Matcher m = TOKEN.matcher(get(BUSCADOR));
            if (!m.find()) throw new IOException("No se ha podido iniciar sesión en la web de ADIF");
            token = m.group(1);
        }
        return token;
    }

    static List<Documento> leerDocumentos(String json) throws IOException {
        List<Documento> lista = new ArrayList<>();
        for (JsonNode f : JSON.readTree(json).path("data")) {
            lista.add(new Documento(texto(f, 0), texto(f, 1), texto(f, 2), texto(f, 3), texto(f, 4), texto(f, 5)));
        }
        return lista;
    }

    static List<Anexo> leerAnexos(String json) throws IOException {
        List<Anexo> lista = new ArrayList<>();
        for (JsonNode f : JSON.readTree(json).path("data")) {
            lista.add(new Anexo(texto(f, 0), texto(f, 1), texto(f, 2)));
        }
        return lista;
    }

    private static String texto(JsonNode fila, int i) {
        JsonNode n = fila.path(i);
        return n.isNull() || n.isMissingNode() ? "" : n.asText().strip();
    }

    private String get(String url) throws IOException {
        HttpResponse<String> r = enviar(ClienteHttp.peticion(url).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " en " + url);
        return r.body();
    }

    private <T> HttpResponse<T> enviar(HttpRequest peticion, HttpResponse.BodyHandler<T> cuerpo) throws IOException {
        try {
            return http.send(peticion, cuerpo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Petición interrumpida", e);
        }
    }
}
