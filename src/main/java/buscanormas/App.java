package buscanormas;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.staticfiles.Location;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Punto de entrada.
 *
 * <pre>
 *   java -jar normas-adif.jar [servir]  [--pdf ruta] [--url url] [--puerto 8080]
 *   java -jar normas-adif.jar actualizar [--pdf ruta] [--url url]
 *   java -jar normas-adif.jar volcar     [ruta.pdf]
 * </pre>
 *
 * Los PDF de las normas que se abren con "Abrir norma" se guardan en {@code datos/normas/}.
 */
public final class App {

    static final String URL_CATALOGO =
            "https://www.adif.es/documents/20124/3371563/CatalogoNormativaTecnica.pdf/";

    /** Estado visible en la interfaz: de dónde salen los datos y cuándo se cargaron. */
    public record Info(String pdf, String url, int normas, String cargado, String error) {
    }

    private final Path pdf;
    private final String url;
    /** Identificador de documento de la web de ADIF (Documentum): 16 caracteres hexadecimales. */
    private static final Pattern ID_ADIF = Pattern.compile("[0-9a-f]{16}");
    private static final Path CARPETA_NORMAS = Path.of("datos", "normas");

    private final AtomicReference<Indice> indice = new AtomicReference<>(new Indice(List.of()));
    private final NormativaAdif adif = new NormativaAdif();
    private volatile Info info;

    App(Path pdf, String url) {
        this.pdf = pdf;
        this.url = url;
        this.info = new Info(pdf.toAbsolutePath().toString(), url, 0, "", "");
    }

    public static void main(String[] args) throws Exception {
        ClienteHttp.usarProxyDelSistema();
        String comando = "servir";
        Path pdf = Path.of("datos", "CatalogoNormativaTecnica.pdf");
        String url = URL_CATALOGO;
        int puerto = 8080;
        List<String> sueltos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--pdf" -> pdf = Path.of(args[++i]);
                case "--url" -> url = args[++i];
                case "--puerto" -> puerto = Integer.parseInt(args[++i]);
                default -> sueltos.add(args[i]);
            }
        }
        if (!sueltos.isEmpty()) comando = sueltos.get(0);
        if (comando.equals("volcar") && sueltos.size() > 1) pdf = Path.of(sueltos.get(1));

        App app = new App(pdf, url);
        switch (comando) {
            case "servir" -> app.servir(puerto);
            case "actualizar" -> {
                try {
                    app.descargar();
                    System.out.println("Descargado en " + pdf.toAbsolutePath());
                } catch (IOException e) {
                    System.err.println("No se pudo descargar el catálogo: " + motivo(e));
                    System.err.println(app.descargaManual());
                    System.exit(1);
                }
            }
            case "volcar" -> volcar(pdf);
            default -> {
                System.err.println("Comando desconocido: " + comando + " (usa servir, actualizar o volcar)");
                System.exit(2);
            }
        }
    }

    void servir(int puerto) {
        try {
            if (!Files.exists(pdf)) descargar();
            cargar();
        } catch (IOException | RuntimeException e) {
            // Se arranca igualmente: la interfaz muestra el error y permite reintentar.
            info = new Info(pdf.toAbsolutePath().toString(), url, 0, "", motivo(e));
            System.err.println("No se pudo cargar el catálogo: " + motivo(e));
            System.err.println(descargaManual());
        }

        Javalin web = Javalin.create(config -> {
            config.showJavalinBanner = false;
            if (App.class.getResource("/public") != null) {
                config.staticFiles.add("/public", Location.CLASSPATH);
            }
        });
        web.get("/api/buscar", ctx -> {
            int limite = Math.min(ctx.queryParamAsClass("limite", Integer.class).getOrDefault(200), 2000);
            ctx.json(indice.get().buscar(
                    ctx.queryParam("q"), ctx.queryParam("especialidad"), ctx.queryParam("tipo"), limite));
        });
        web.get("/api/facetas", ctx -> ctx.json(indice.get().facetas()));
        web.get("/api/info", ctx -> ctx.json(info));
        web.get("/ficha/{pagina}", ctx -> {
            int n = ctx.pathParamAsClass("pagina", Integer.class).get();
            ctx.contentType("application/pdf")
                    .header("Content-Disposition", "inline; filename=\"ficha-" + n + ".pdf\"")
                    .result(ficha(n));
        });
        web.get("/norma", this::abrirNorma);
        web.get("/norma/doc/{id}", this::servirNorma);
        // El navegador envía el PDF del catálogo que el usuario ha descargado (cuando Java no puede).
        web.post("/api/catalogo", ctx -> {
            try {
                synchronized (this) {
                    cargarSubido(ctx.bodyInputStream());
                }
                adif.olvidar();
                ctx.json(info);
            } catch (IOException | RuntimeException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", motivo(e)));
            }
        });
        web.post("/api/actualizar", ctx -> {
            try {
                synchronized (this) {
                    descargar();
                    cargar();
                }
                adif.olvidar();
                ctx.json(info);
            } catch (IOException | RuntimeException e) {
                ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", motivo(e)));
            }
        });
        web.start(puerto);
        System.out.println("Buscador de normas en http://localhost:" + puerto
                + "  (" + info.normas() + " normas)");
    }

    /**
     * "Abrir norma": busca el código en la web de ADIF. Con una versión vigente redirige a su PDF;
     * con varias (colecciones como "PAV") o ninguna, muestra una página con la lista o el aviso.
     */
    private void abrirNorma(Context ctx) {
        String codigo = ctx.queryParam("codigo");
        if (codigo == null || codigo.isBlank()) throw new BadRequestResponse("Falta el código");
        try {
            List<NormativaAdif.Documento> documentos = adif.vigentes(codigo);
            if (documentos.size() == 1) {
                ctx.redirect("/norma/doc/" + documentos.get(0).id());
            } else {
                ctx.html(paginaDocumentos(codigo, documentos));
            }
        } catch (IOException e) {
            ctx.status(HttpStatus.BAD_GATEWAY).html(pagina("No se ha podido consultar la web de ADIF",
                    "<p>" + escapar(motivo(e)) + "</p><p>Comprueba la conexión a internet.</p>"));
        }
    }

    /** El PDF de una versión: desde datos/normas/ si ya se abrió antes; si no, se descarga de ADIF. */
    private void servirNorma(Context ctx) throws IOException {
        String id = ctx.pathParam("id");
        if (!ID_ADIF.matcher(id).matches()) throw new BadRequestResponse("Identificador no válido");
        Path norma;
        try {
            norma = normaEnDisco(id);
        } catch (IOException e) {
            ctx.status(HttpStatus.BAD_GATEWAY).html(pagina("No se ha podido descargar la norma",
                    "<p>" + escapar(motivo(e)) + "</p>"));
            return;
        }
        if (norma == null) {
            ctx.status(HttpStatus.NOT_FOUND).html(pagina("Sin PDF", "<p>Esta norma no tiene PDF en la web de ADIF.</p>"));
            return;
        }
        ctx.contentType("application/pdf")
                .header("Content-Disposition", "inline; filename=\"" + norma.getFileName() + "\"")
                .result(Files.newInputStream(norma));
    }

    private Path normaEnDisco(String id) throws IOException {
        Path carpeta = CARPETA_NORMAS.resolve(id);
        if (Files.isDirectory(carpeta)) {
            try (Stream<Path> ficheros = Files.list(carpeta)) {
                Path guardado = ficheros.filter(f -> f.toString().endsWith(".pdf")).findFirst().orElse(null);
                if (guardado != null) return guardado;
            }
        }
        NormativaAdif.Anexo anexo = adif.pdfDe(id);
        if (anexo == null) return null;
        Path destino = carpeta.resolve(nombreSeguro(anexo.nombre()));
        adif.descargar(anexo.id(), destino);
        return destino;
    }

    /** El nombre viene de la web de ADIF: solo caracteres inocuos, para usarlo como fichero y en cabeceras. */
    static String nombreSeguro(String nombre) {
        String limpio = nombre.replaceAll("[^A-Za-z0-9._ -]", "_").replaceFirst("^[.\\s]+", "");
        if (limpio.isEmpty()) limpio = "norma";
        return limpio.toLowerCase().endsWith(".pdf") ? limpio : limpio + ".pdf";
    }

    private static String paginaDocumentos(String codigo, List<NormativaAdif.Documento> documentos) {
        if (documentos.isEmpty()) {
            return pagina("No encontrada", "<p>No se ha encontrado <strong>" + escapar(codigo)
                    + "</strong> entre las normas vigentes de la web de ADIF.</p><p><a href=\""
                    + NormativaAdif.WEB + "/\">Buscador oficial de ADIF</a></p>");
        }
        StringBuilder lista = new StringBuilder("<p>" + documentos.size() + " documentos vigentes para <strong>"
                + escapar(codigo) + "</strong>:</p><ul>");
        for (NormativaAdif.Documento d : documentos) {
            lista.append("<li><a href=\"/norma/doc/").append(d.id()).append("\">").append(escapar(d.codigo()))
                    .append("</a> — ").append(escapar(d.titulo()))
                    .append(" <small>").append(escapar(d.edicion())).append(" · ").append(escapar(d.fecha()))
                    .append("</small></li>");
        }
        return pagina(codigo, lista.append("</ul>").toString());
    }

    private static String pagina(String titulo, String cuerpo) {
        return "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escapar(titulo) + "</title><style>"
                + ":root{color-scheme:light dark;font-family:system-ui,sans-serif}"
                + "body{max-width:900px;margin:24px auto;padding:0 16px;line-height:1.5}"
                + "li{margin:4px 0}small{opacity:.7}a{color:#0b6e4f}"
                + "@media(prefers-color-scheme:dark){a{color:#4cc38a}}"
                + "</style></head><body><h1>" + escapar(titulo) + "</h1>" + cuerpo + "</body></html>";
    }

    private static String escapar(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Una sola página del catálogo como PDF aparte: abre al instante, no los 57 MB del catálogo. */
    synchronized byte[] ficha(int pagina) throws IOException {
        try (PDDocument origen = Loader.loadPDF(pdf.toFile()); PDDocument una = new PDDocument()) {
            if (pagina < 1 || pagina > origen.getNumberOfPages()) throw new NotFoundResponse("No existe esa página");
            una.importPage(origen.getPage(pagina - 1));
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            una.save(salida);
            return salida.toByteArray();
        }
    }

    void cargar() throws IOException {
        publicar(CatalogoPdf.leer(pdf));
    }

    /**
     * Catálogo subido desde la interfaz: solo sustituye al actual si es un PDF con fichas
     * reconocibles, para que un fichero equivocado no deje el buscador vacío.
     */
    void cargarSubido(InputStream subido) throws IOException {
        Path absoluto = pdf.toAbsolutePath();
        Files.createDirectories(absoluto.getParent());
        Path temporal = Files.createTempFile(absoluto.getParent(), "catalogo", ".part");
        try {
            Files.copy(subido, temporal, StandardCopyOption.REPLACE_EXISTING);
            if (!esPdf(temporal)) throw new IOException("El fichero no es un PDF.");
            List<Norma> normas = CatalogoPdf.leer(temporal);
            if (normas.isEmpty()) {
                throw new IOException("No parece el Catálogo de Normativa Técnica: no se ha reconocido ninguna ficha.");
            }
            Files.move(temporal, absoluto, StandardCopyOption.REPLACE_EXISTING);
            publicar(normas);
            System.out.println("Catálogo cargado desde el navegador: " + normas.size() + " normas");
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    private void publicar(List<Norma> normas) {
        indice.set(new Indice(normas));
        info = new Info(pdf.toAbsolutePath().toString(), url, normas.size(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), "");
        if (normas.isEmpty()) {
            System.err.println("Aviso: no se ha reconocido ninguna norma en el PDF. "
                    + "Revisa el formato con: java -jar normas-adif.jar volcar " + pdf);
        }
    }

    /** Descarga a un temporal y solo sustituye el PDF anterior si lo recibido es realmente un PDF. */
    void descargar() throws IOException {
        System.out.println("Descargando " + url + " ...");
        HttpClient cliente = ClienteHttp.cliente().build();
        HttpRequest peticion = ClienteHttp.peticion(url).build();
        Path absoluto = pdf.toAbsolutePath();
        Files.createDirectories(absoluto.getParent());
        Path temporal = Files.createTempFile(absoluto.getParent(), "catalogo", ".part");
        try {
            HttpResponse<Path> r = cliente.send(peticion, HttpResponse.BodyHandlers.ofFile(temporal));
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                throw new IOException("ADIF ha rechazado la descarga (HTTP " + r.statusCode() + ").");
            }
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " al descargar " + url);
            if (!esPdf(temporal)) throw new IOException("La URL no ha devuelto un PDF.");
            Files.move(temporal, absoluto, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Descarga interrumpida", e);
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    /** Algunos errores de red (conexión rechazada, sin DNS...) no traen mensaje. */
    static String motivo(Exception e) {
        if (e.getMessage() != null && !e.getMessage().isBlank()) return e.getMessage();
        return e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                ? "No se ha podido conectar con el servidor."
                : "Error inesperado (" + e.getClass().getSimpleName() + ").";
    }

    private static boolean esPdf(Path fichero) throws IOException {
        try (InputStream in = Files.newInputStream(fichero)) {
            return new String(in.readNBytes(5), StandardCharsets.US_ASCII).startsWith("%PDF");
        }
    }

    /**
     * Si la red o ADIF no dejan descargar desde Java, el navegador sí suele poder. La interfaz
     * muestra estos mismos pasos a partir de {@link Info#url()} y {@link Info#pdf()}.
     */
    String descargaManual() {
        return "Descarga el PDF con el navegador desde " + url + " y guárdalo como "
                + pdf.toAbsolutePath() + "; después vuelve a arrancar.";
    }

    /** Muestra lo que se extrae del PDF para poder ajustar {@link CatalogoPdf} al formato real. */
    static void volcar(Path pdf) throws IOException {
        // UTF-8 explícito: lo normal es redirigirlo a un fichero (volcar x.pdf > volcado.txt).
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        List<CatalogoPdf.Pagina> paginas = CatalogoPdf.extraerPaginas(pdf);
        for (CatalogoPdf.Pagina p : paginas) {
            out.println("=== página " + p.numero() + " ===");
            out.println(p.campos().strip());
            out.println("--- objeto ---");
            out.println(p.objeto().strip());
            out.println();
        }
        List<Norma> normas = CatalogoPdf.interpretar(paginas);
        out.println("=== FICHAS RECONOCIDAS (" + normas.size() + ") ===");
        for (Norma n : normas) {
            out.printf("p%-3d %-20s | %s | %s | %s | %s | deroga: %s%n", n.pagina(), n.codigo(), n.titulo(),
                    n.edicion(), n.fecha(), n.especialidad(), n.deroga());
        }
        out.println();
        out.println("Sin código: " + normas.stream().filter(n -> n.codigo().isEmpty()).map(Norma::pagina).toList());
        out.println("Sin título: " + normas.stream().filter(n -> n.titulo().isEmpty()).map(Norma::pagina).toList());
        out.println("Sin fecha:  " + normas.stream().filter(n -> n.fecha().isEmpty()).map(Norma::pagina).toList());
        out.println("Sin objeto: " + normas.stream().filter(n -> n.objeto().isEmpty()).map(Norma::pagina).toList());
    }
}
