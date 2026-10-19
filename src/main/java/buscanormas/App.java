package buscanormas;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.javalin.Javalin;
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
 */
public final class App {

    static final String URL_CATALOGO =
            "https://www.adif.es/documents/20124/3371563/CatalogoNormativaTecnica.pdf/";

    /** Estado visible en la interfaz: de dónde salen los datos y cuándo se cargaron. */
    public record Info(String pdf, String url, int normas, String cargado, String error) {
    }

    private final Path pdf;
    private final String url;
    private final AtomicReference<Indice> indice = new AtomicReference<>(new Indice(List.of()));
    private volatile Info info;

    App(Path pdf, String url) {
        this.pdf = pdf;
        this.url = url;
        this.info = new Info(pdf.toString(), url, 0, "", "");
    }

    public static void main(String[] args) throws Exception {
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
                app.descargar();
                System.out.println("Descargado en " + pdf.toAbsolutePath());
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
            info = new Info(pdf.toString(), url, 0, "", e.getMessage());
            System.err.println("No se pudo cargar el catálogo: " + e.getMessage());
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
        web.post("/api/actualizar", ctx -> {
            try {
                synchronized (this) {
                    descargar();
                    cargar();
                }
                ctx.json(info);
            } catch (IOException | RuntimeException e) {
                ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", String.valueOf(e.getMessage())));
            }
        });
        web.start(puerto);
        System.out.println("Buscador de normas en http://localhost:" + puerto
                + "  (" + info.normas() + " normas)");
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
        List<Norma> normas = CatalogoPdf.leer(pdf);
        indice.set(new Indice(normas));
        info = new Info(pdf.toString(), url, normas.size(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), "");
        if (normas.isEmpty()) {
            System.err.println("Aviso: no se ha reconocido ninguna norma en el PDF. "
                    + "Revisa el formato con: java -jar normas-adif.jar volcar " + pdf);
        }
    }

    /** Descarga a un temporal y solo sustituye el PDF anterior si lo recibido es realmente un PDF. */
    void descargar() throws IOException {
        System.out.println("Descargando " + url + " ...");
        HttpClient cliente = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest peticion = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (buscador-normas-adif)")
                .timeout(Duration.ofMinutes(2))
                .build();
        Path absoluto = pdf.toAbsolutePath();
        Files.createDirectories(absoluto.getParent());
        Path temporal = Files.createTempFile(absoluto.getParent(), "catalogo", ".part");
        try {
            HttpResponse<Path> r = cliente.send(peticion, HttpResponse.BodyHandlers.ofFile(temporal));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " al descargar " + url);
            try (InputStream in = Files.newInputStream(temporal)) {
                byte[] cabecera = in.readNBytes(5);
                if (!new String(cabecera).startsWith("%PDF")) {
                    throw new IOException("La URL no ha devuelto un PDF: " + url);
                }
            }
            Files.move(temporal, absoluto, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Descarga interrumpida", e);
        } finally {
            Files.deleteIfExists(temporal);
        }
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
