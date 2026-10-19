package buscanormas;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;

/**
 * Lee el PDF del catálogo y lo convierte en una lista de {@link Norma}.
 *
 * <p>Formato del catálogo (20ª edición, junio 2026): una ficha por página. Arriba a la izquierda
 * hay una miniatura de la portada de la norma (su texto sale con basura de OCR y se descarta);
 * a la derecha, a partir de x≈300, los campos etiquetados ("Título:", "Código:", "Edición:",
 * "Fecha de edición:", "Especialidad:", "Deroga a:"); debajo, a todo el ancho,
 * "Objeto del documento:". Las páginas sin "Título:" (portada, separadores de especialidad)
 * se ignoran.
 */
public final class CatalogoPdf {

    /** Texto de las dos zonas útiles de una página. */
    public record Pagina(int numero, String campos, String objeto) {
    }

    /** Borde izquierdo de la columna de campos; la miniatura de la portada queda a la izquierda. */
    private static final float X_CAMPOS = 290;
    /** Por debajo empieza el pie de página ("Catálogo de Normativa Técnica · Junio 2026 · n"). */
    private static final float Y_PIE = 800;
    /** Si una ficha no tuviera la etiqueta "Objeto del documento:", posición habitual. */
    private static final float Y_OBJETO_HABITUAL = 485;
    private static final float MARGEN = 6;

    /** Aviso legal de cada página: es una capa aparte que se cuela, recortada, en la zona del objeto. */
    private static final List<String> AVISOS = List.of(
            "Este documento ha sido aprobado por el Comité de Normativa.",
            "Existe la posibilidad de que alguno de los contenidos estén sujetos a derechos de patente",
            "TODOS LOS DERECHOS RESERVADOS, POR LO QUE ESTE DOCUMENTO NO PUEDE SER DISTRIBUIDO",
            "Cualquier consulta sobre normativa técnica puede realizarse");

    private static final String TITULO = "Título:";
    private static final String OBJETO = "Objeto del documento:";
    private static final Pattern ETIQUETA = Pattern.compile(
            "^(t[ií]tulo|c[oó]digo|edici[oó]n|fecha de edici[oó]n|especialidad|deroga a)\\s*:\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private CatalogoPdf() {
    }

    public static List<Norma> leer(Path pdf) throws IOException {
        return interpretar(extraerPaginas(pdf));
    }

    public static List<Pagina> extraerPaginas(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return extraerPaginas(doc);
        }
    }

    static List<Pagina> extraerPaginas(PDDocument doc) throws IOException {
        Localizador loc = new Localizador();
        loc.getText(doc);

        List<Pagina> paginas = new ArrayList<>();
        for (Map.Entry<Integer, Float> e : loc.tituloY.entrySet()) {
            int n = e.getKey();
            float arriba = e.getValue() - MARGEN;
            float objeto = loc.objetoY.getOrDefault(n, Y_OBJETO_HABITUAL) - MARGEN;
            PDPage page = doc.getPage(n - 1);
            float ancho = page.getCropBox().getWidth();

            PDFTextStripperByArea zonas = new PDFTextStripperByArea();
            zonas.setSortByPosition(true);
            zonas.addRegion("campos", new Rectangle2D.Float(X_CAMPOS, arriba, ancho - X_CAMPOS, objeto - arriba));
            zonas.addRegion("objeto", new Rectangle2D.Float(0, objeto, ancho, Y_PIE - objeto));
            zonas.extractRegions(page);
            paginas.add(new Pagina(n, zonas.getTextForRegion("campos"), zonas.getTextForRegion("objeto")));
        }
        return paginas;
    }

    public static List<Norma> interpretar(List<Pagina> paginas) {
        List<Norma> normas = new ArrayList<>();
        for (Pagina p : paginas) {
            Map<String, StringBuilder> campos = campos(p.campos());
            String codigo = codigo(valor(campos, "codigo"));
            if (codigo.isEmpty() && valor(campos, "titulo").isEmpty()) continue;
            normas.add(new Norma(codigo, valor(campos, "titulo"), edicion(valor(campos, "edicion")),
                    valor(campos, "fecha de edicion"), valor(campos, "especialidad"),
                    edicion(valor(campos, "deroga a")),
                    objeto(p.objeto()), Norma.tipoDe(codigo), p.numero()));
        }
        return normas;
    }

    /** Cada línea que empieza por una etiqueta abre un campo; las demás continúan el anterior. */
    private static Map<String, StringBuilder> campos(String texto) {
        Map<String, StringBuilder> campos = new LinkedHashMap<>();
        StringBuilder actual = null;
        for (String linea : texto.split("\\R")) {
            String l = linea.strip();
            if (l.isEmpty()) continue;
            Matcher m = ETIQUETA.matcher(l);
            if (m.matches()) {
                actual = campos.computeIfAbsent(sinAcentos(m.group(1)).toLowerCase(Locale.ROOT),
                        k -> new StringBuilder());
                l = m.group(2);
            }
            if (actual != null && !l.isEmpty()) {
                if (!actual.isEmpty()) actual.append(' ');
                actual.append(l);
            }
        }
        return campos;
    }

    private static String valor(Map<String, StringBuilder> campos, String clave) {
        StringBuilder sb = campos.get(clave);
        return sb == null ? "" : limpiar(sb.toString());
    }

    /** Une las líneas en párrafos: un punto final de línea seguido de otra línea marca párrafo nuevo. */
    private static String objeto(String texto) {
        String t = texto.strip();
        if (t.startsWith(OBJETO)) t = t.substring(OBJETO.length());
        StringBuilder sb = new StringBuilder();
        for (String linea : t.split("\\R")) {
            String l = linea.strip();
            if (l.isEmpty() || AVISOS.stream().anyMatch(a -> a.startsWith(l) || l.startsWith(a))) continue;
            if (!sb.isEmpty()) sb.append(sb.charAt(sb.length() - 1) == '.' || l.startsWith("·") ? "\n" : " ");
            sb.append(l);
        }
        return limpiar(sb.toString());
    }

    /** Artefactos de la fuente del PDF: "1~" es "1ª" y a veces parte palabras ("Edició n"). */
    private static String limpiar(String s) {
        return s.replaceAll("[ \\t]+", " ").replaceAll("(\\d)~", "$1ª").replaceAll("ió n\\b", "ión").strip();
    }

    /**
     * Espacios espurios y "O" por "0" dentro del código: "NAP 2-4 -1.0" → "NAP 2-4-1.0",
     * "ET 03.366. 752.8" → "ET 03.366.752.8", "PPT 9-0-7.O" → "PPT 9-0-7.0", "NRS 7-3-0.0. (NAS 730)" →
     * "NRS 7-3-0.0 (NAS 730)".
     */
    static String codigo(String s) {
        return s.replaceAll("(?<=[\\dO])\\s*([.\\-])\\s*(?=[\\dO])", "$1")
                .replaceAll("(?<=[\\d.\\-])O(?=[\\d.\\-]|\\s|$)", "0")
                .replaceAll("\\.(?=\\s*\\(|$)", "");
    }

    /** "1ª Edición+Ml" → "1ª Edición + M1" (las modificaciones M1, M2... salen a veces como "Ml"). */
    private static String edicion(String s) {
        return s.replaceAll("\\bM[lI]\\b", "M1").replaceAll("\\s*\\+\\s*", " + ");
    }

    static String sinAcentos(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Primera pasada: en qué páginas hay ficha (etiqueta "Título:" en la columna derecha) y dónde empieza el objeto. */
    private static final class Localizador extends PDFTextStripper {
        final Map<Integer, Float> tituloY = new LinkedHashMap<>();
        final Map<Integer, Float> objetoY = new HashMap<>();

        Localizador() {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String texto, List<TextPosition> posiciones) {
            int pagina = getCurrentPageNo();
            int i = texto.indexOf(TITULO);
            if (i >= 0 && i < posiciones.size() && posiciones.get(i).getXDirAdj() >= X_CAMPOS) {
                tituloY.putIfAbsent(pagina, posiciones.get(i).getYDirAdj());
            }
            i = texto.indexOf(OBJETO);
            if (i >= 0 && i < posiciones.size()) {
                objetoY.putIfAbsent(pagina, posiciones.get(i).getYDirAdj());
            }
        }
    }
}
