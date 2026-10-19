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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
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
 *   java -jar normas-adif.jar mapear     [--pdf ruta] [--salida mapa/mapa-normas.json]
 * </pre>
 *
 * Los PDF de las normas que se abren con "Abrir norma" se guardan en {@code datos/normas/}.
 */
public final class App {

    static final String URL_CATALOGO =
            "https://www.adif.es/documents/20124/3371563/CatalogoNormativaTecnica.pdf/";

    /**
     * Si Java puede usar la web de ADIF desde esta red. Si no, "Abrir norma" usa el mapa de normas
     * ({@link MapaNormas}) y el navegador descarga el PDF directamente de ADIF.
     */
    public record AccesoAdif(boolean disponible, String motivo, String buscador, String mapa, int normasMapa) {
    }

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
    private final MapaNormas mapa = new MapaNormas();
    static final Path FICHERO_MAPA = Path.of("datos", "mapa-normas.json");
    private volatile Map<String, Norma> porCodigo = Map.of();
    private volatile Boolean accesoOk;
    private volatile String accesoMotivo = "";
    private volatile long accesoComprobado;
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
        Path salida = Path.of("mapa", "mapa-normas.json");
        List<String> sueltos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--pdf" -> pdf = Path.of(args[++i]);
                case "--url" -> url = args[++i];
                case "--puerto" -> puerto = Integer.parseInt(args[++i]);
                case "--salida" -> salida = Path.of(args[++i]);
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
            case "mapear" -> {
                try {
                    app.mapear(salida);
                } catch (IOException e) {
                    System.err.println("No se pudo generar el mapa de normas: " + motivo(e));
                    System.exit(1);
                }
            }
            default -> {
                System.err.println("Comando desconocido: " + comando + " (usa servir, actualizar, volcar o mapear)");
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
        mapa.cargarLocal(FICHERO_MAPA);
        Thread.ofVirtual().start(() -> mapa.actualizarDesde(MapaNormas.URL_GITHUB, FICHERO_MAPA));

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
        web.get("/api/adif", ctx -> ctx.json(accesoAdif()));
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
     * "Abrir norma": si Java llega a la web de ADIF, busca el código en ella al momento. Con una
     * versión vigente redirige a su PDF; con varias (colecciones como "PAV") o ninguna, muestra una
     * página con la lista o el aviso. Si no llega, usa el mapa de normas.
     */
    private void abrirNorma(Context ctx) {
        String codigo = ctx.queryParam("codigo");
        if (codigo == null || codigo.isBlank()) throw new BadRequestResponse("Falta el código");
        if (accesoAdif().disponible()) {
            try {
                List<NormativaAdif.Documento> documentos = adif.vigentes(codigo);
                if (documentos.size() == 1) {
                    ctx.redirect("/norma/doc/" + documentos.get(0).id());
                } else {
                    ctx.html(paginaDocumentos(codigo, documentos));
                }
                return;
            } catch (IOException e) {
                System.err.println("Web de ADIF no disponible (" + motivo(e) + "); se usa el mapa de normas");
            }
        }
        abrirConMapa(ctx, codigo);
    }

    /**
     * Sin acceso desde Java: el enlace de descarga directa de ADIF sí funciona en el navegador, así
     * que se redirige a él con el identificador que da el mapa.
     */
    private void abrirConMapa(Context ctx, String codigo) {
        List<MapaNormas.Entrada> entradas = mapa.buscar(codigo).stream().filter(e -> !e.anexo().isEmpty()).toList();
        if (entradas.isEmpty()) {
            ctx.html(paginaNoEnMapa(codigo));
            return;
        }
        if (entradas.size() > 1) {
            ctx.html(paginaEntradas(codigo, entradas));
            return;
        }
        MapaNormas.Entrada e = entradas.get(0);
        Norma n = porCodigo.get(codigo);
        if (n != null && !mismaFecha(n.fecha(), e.fecha())) {
            ctx.html(paginaFechaDistinta(n, e));
            return;
        }
        ctx.redirect(NormativaAdif.urlDescarga(e.anexo()));
    }

    /** Se comprueba como mucho cada 5 minutos: la red no cambia a cada búsqueda. */
    AccesoAdif accesoAdif() {
        if (accesoOk == null || System.currentTimeMillis() - accesoComprobado > 5 * 60_000) {
            try {
                adif.comprobarAcceso();
                accesoOk = true;
                accesoMotivo = "";
            } catch (IOException e) {
                accesoOk = false;
                accesoMotivo = motivo(e);
            }
            accesoComprobado = System.currentTimeMillis();
        }
        return new AccesoAdif(accesoOk, accesoMotivo, NormativaAdif.BUSCADOR, mapa.generado(), mapa.normas());
    }

    private static final List<String> MESES = List.of("enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre");
    private static final Pattern FECHA_CATALOGO = Pattern.compile("([a-z]+)\\s+de\\s+(\\d{4})");
    private static final Pattern FECHA_ADIF = Pattern.compile("\\d{1,2}/(\\d{1,2})/(\\d{4})");

    /**
     * ¿La versión del mapa es la de la ficha del catálogo? Se compara mes y año ("Enero de 2024"
     * frente a "24/01/2024"). Si alguna fecha no se entiende, no se avisa.
     */
    static boolean mismaFecha(String catalogo, String adif) {
        Matcher c = FECHA_CATALOGO.matcher(CatalogoPdf.sinAcentos(catalogo).toLowerCase(Locale.ROOT));
        Matcher a = FECHA_ADIF.matcher(adif);
        if (!c.find() || !a.find()) return true;
        int mes = MESES.indexOf(c.group(1)) + 1;
        return mes == 0 || (mes == Integer.parseInt(a.group(1)) && c.group(2).equals(a.group(2)));
    }

    /** El PDF de una versión: desde datos/normas/ si ya se abrió antes; si no, se descarga de ADIF. */
    private void servirNorma(Context ctx) throws IOException {
        String id = ctx.pathParam("id");
        if (!ID_ADIF.matcher(id).matches()) throw new BadRequestResponse("Identificador no válido");
        Path carpeta = CARPETA_NORMAS.resolve(id);
        Path guardada = normaGuardada(carpeta);
        if (guardada != null) {
            enviarPdf(ctx, guardada);
            return;
        }
        NormativaAdif.Anexo anexo;
        try {
            anexo = adif.pdfDe(id);
        } catch (IOException e) {
            ctx.status(HttpStatus.BAD_GATEWAY).html(pagina("No se ha podido consultar la web de ADIF",
                    "<p>" + escapar(motivo(e)) + "</p><p><a href=\"" + NormativaAdif.BUSCADOR
                    + "\">Buscador oficial de ADIF</a></p>"));
            return;
        }
        if (anexo == null) {
            ctx.status(HttpStatus.NOT_FOUND).html(pagina("Sin PDF", "<p>Esta norma no tiene PDF en la web de ADIF.</p>"));
            return;
        }
        Path destino = carpeta.resolve(nombreSeguro(anexo.nombre()));
        try {
            adif.descargar(anexo.id(), destino);
        } catch (IOException e) {
            // Java no puede descargarla, pero el enlace de ADIF funciona sin sesión: que la descargue el navegador.
            ctx.redirect(NormativaAdif.urlDescarga(anexo.id()));
            return;
        }
        enviarPdf(ctx, destino);
    }

    private static Path normaGuardada(Path carpeta) throws IOException {
        if (!Files.isDirectory(carpeta)) return null;
        try (Stream<Path> ficheros = Files.list(carpeta)) {
            return ficheros.filter(f -> f.toString().endsWith(".pdf")).findFirst().orElse(null);
        }
    }

    private static void enviarPdf(Context ctx, Path pdf) throws IOException {
        ctx.contentType("application/pdf")
                .header("Content-Disposition", "inline; filename=\"" + pdf.getFileName() + "\"")
                .result(Files.newInputStream(pdf));
    }

    /** Norma que no está en el mapa (nueva, o el mapa no pudo consultarla): instrucciones para buscarla a mano. */
    private String paginaNoEnMapa(String codigo) {
        return pagina("Buscar en la web de ADIF", "<p>Esta aplicación no puede consultar la web de ADIF desde tu red"
                + " y <strong>" + escapar(codigo) + "</strong> no está en el mapa de normas"
                + (mapa.generado().isEmpty() ? "" : " (actualizado el " + escapar(fechaCorta(mapa.generado())) + ")")
                + ".</p><p>Búscala en la web oficial:</p><ol>"
                + "<li><a href=\"" + NormativaAdif.BUSCADOR + "\">Abre el buscador de ADIF</a>.</li>"
                + "<li>Despliega la sección «Búsqueda».</li>"
                + "<li>Escribe <span class=\"codigo\">" + escapar(codigoParaBuscar(codigo))
                + "</span> en «Código» y pulsa Buscar.</li></ol>");
    }

    private static String paginaEntradas(String codigo, List<MapaNormas.Entrada> entradas) {
        StringBuilder lista = new StringBuilder("<p>" + entradas.size() + " documentos vigentes para <strong>"
                + escapar(codigo) + "</strong> (se descargan de la web de ADIF):</p><ul>");
        for (MapaNormas.Entrada e : entradas) {
            lista.append("<li><a href=\"").append(NormativaAdif.urlDescarga(e.anexo())).append("\">")
                    .append(escapar(e.codigo())).append("</a> — ").append(escapar(e.titulo()))
                    .append(" <small>").append(escapar(e.edicion())).append(" · ").append(escapar(e.fecha()))
                    .append("</small></li>");
        }
        return pagina(codigo, lista.append("</ul>").toString());
    }

    /** El mapa apunta a una versión con otra fecha que la ficha: puede haber una edición más nueva. */
    private static String paginaFechaDistinta(Norma n, MapaNormas.Entrada e) {
        return pagina(n.codigo(), "<p>El catálogo indica <strong>" + escapar(n.edicion()) + "</strong> de <strong>"
                + escapar(n.fecha()) + "</strong>, pero el mapa de normas apunta a <strong>" + escapar(e.codigo())
                + "</strong> del <strong>" + escapar(e.fecha()) + "</strong>. Puede que ADIF haya publicado una"
                + " edición que el mapa aún no recoge.</p><ul>"
                + "<li><a href=\"" + NormativaAdif.urlDescarga(e.anexo()) + "\">Descargar la del mapa ("
                + escapar(e.fecha()) + ")</a></li>"
                + "<li><a href=\"" + NormativaAdif.BUSCADOR + "\">Buscarla en la web de ADIF</a> (sección «Búsqueda»,"
                + " campo «Código»: <span class=\"codigo\">" + escapar(codigoParaBuscar(n.codigo())) + "</span>)</li></ul>");
    }

    /** "IT 17 (NAS 117)" → "IT 17": el buscador de ADIF no conoce el código alternativo. */
    static String codigoParaBuscar(String codigo) {
        return codigo.replaceAll("\\s*\\(.*\\)", "").strip();
    }

    /** "2026-10-19T09:15:00Z" → "19/10/2026". */
    static String fechaCorta(String instante) {
        return instante.length() >= 10
                ? instante.substring(8, 10) + "/" + instante.substring(5, 7) + "/" + instante.substring(0, 4)
                : instante;
    }

    /**
     * Genera el mapa de normas consultando la web de ADIF por cada código del catálogo (comando
     * {@code mapear}; lo ejecuta GitHub cada semana). Si fallan demasiadas, no se guarda nada.
     */
    void mapear(Path salida) throws IOException {
        List<Norma> catalogo = CatalogoPdf.leer(pdf);
        adif.comprobarAcceso();
        MapaNormas.Datos datos = MapaNormas.generar(catalogo, codigo -> {
            List<MapaNormas.Entrada> entradas = new ArrayList<>();
            for (NormativaAdif.Documento d : adif.vigentes(codigo)) {
                NormativaAdif.Anexo a = adif.pdfDe(d.id());
                entradas.add(new MapaNormas.Entrada(d.id(), d.codigo(), d.titulo(), d.edicion(), d.fecha(),
                        a == null ? "" : a.id(), a == null ? "" : a.nombre()));
                MapaNormas.dormir(100);
            }
            return entradas;
        }, 300, System.out);
        long distintos = catalogo.stream().map(Norma::codigo).distinct().count();
        if (datos.normas().size() < distintos * 0.9) {
            throw new IOException("Solo se han podido consultar " + datos.normas().size() + " de " + distintos
                    + " normas; no se guarda el mapa.");
        }
        MapaNormas.escribir(datos, salida);
        System.out.println("Mapa de " + datos.normas().size() + " normas guardado en " + salida.toAbsolutePath());
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
                + ".codigo{font:600 1.4rem ui-monospace,Consolas,monospace}"
                + "button{font:inherit;padding:8px 14px;border-radius:8px;cursor:pointer}"
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
        Map<String, Norma> mapaCodigos = new java.util.HashMap<>();
        normas.forEach(n -> mapaCodigos.putIfAbsent(n.codigo(), n));
        porCodigo = mapaCodigos;
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
