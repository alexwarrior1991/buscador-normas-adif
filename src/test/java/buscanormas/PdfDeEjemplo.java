package buscanormas;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Genera un catálogo INVENTADO que imita la maquetación del real: una ficha por página con
 * campos a la derecha (x≈305), una miniatura de portada a la izquierda cuyo texto comparte
 * líneas con los campos, el objeto debajo a todo el ancho, pie de página y aviso legal.
 * Incluye una página separadora de especialidad, que no es una ficha.
 */
final class PdfDeEjemplo {

    private static final PDType1Font NORMAL = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font NEGRITA = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final float ALTO = 842;

    private record Ficha(String titulo1, String titulo2, String codigo, String edicion, String fecha,
            String especialidad, String deroga, String[] objeto) {
    }

    private PdfDeEjemplo() {
    }

    static void crear(Path destino) throws IOException {
        Ficha[] fichas = {
                new Ficha("Canaletas prefabricadas de hormigón para cables.", null, "ET 03.305.001.4",
                        "1~ Edición + Ml+M2", "Enero de 2024", "Plataforma", "ET 03.305.001.4. 1~ Ed.+Ml.",
                        new String[] {"La presente Especificación Técnica (ET) describe las características",
                                "de las canaletas prefabricadas de hormigón para cables.",
                                "Es de aplicación a todos los cuerpos y tapas de canaletas."}),
                new Ficha("Nomenclatura de estructuras, túneles y obras de", "drenaje para proyectos de plataforma.",
                        "NAP 0-1-0.0", "1~ Edició n", "Enero de 2016", "Plataforma", "IGP-0.4",
                        new String[] {"El objeto de la presente Norma es adoptar una nomenclatura",
                                "única de referencia en los documentos de proyecto."}),
                null, // separador de especialidad
                new Ficha("Carriles. Soldadura aluminotérmica.", null, "NAV 3-4-0.0", "2~ Edición",
                        "Marzo de 2019", "Vía", "",
                        new String[] {"Define el procedimiento de soldadura aluminotérmica de carriles."}),
        };

        try (PDDocument doc = new PDDocument()) {
            int numero = 0;
            for (Ficha f : fichas) {
                PDPage pagina = new PDPage(new PDRectangle(595.5f, ALTO));
                doc.addPage(pagina);
                numero++;
                try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                    escribir(cs, NORMAL, 9, 279, 15, "PÚBLICO");
                    if (f == null) {
                        escribir(cs, NEGRITA, 30, 250, 570, "VÍA");
                    } else {
                        ficha(cs, f);
                    }
                    escribir(cs, NORMAL, 9, 72, 814, "Catálogo de Normativa Técnica");
                    escribir(cs, NORMAL, 9, 518, 814, String.valueOf(numero));
                    escribir(cs, NORMAL, 7, 318, 12, "Este documento ha sido aprobado por el Comité de Normativa.");
                    escribir(cs, NORMAL, 7, 73, 29, "TODOS LOS DERECHOS RESERVADOS, POR LO QUE ESTE DOCUMENTO "
                            + "NO PUEDE SER DISTRIBUIDO, COMUNICADO, COPIADO NI EDITADO");
                }
            }
            doc.save(destino.toFile());
        }
    }

    private static void ficha(PDPageContentStream cs, Ficha f) throws IOException {
        // Miniatura de la portada: texto basura en las mismas líneas que los campos.
        escribir(cs, NORMAL, 4, 106, 254, "[T CJ,J0§.001,4");
        escribir(cs, NORMAL, 5, 106, 270, "NORMA ADIF PLATAFORMA");
        escribir(cs, NORMAL, 10, 107, 306, "PREFABRICADAS DE");
        escribir(cs, NORMAL, 5, 106, 349, "l:! t:DIC10-N: )IJLIO 201B");

        escribir(cs, NEGRITA, 11, 304, 190, "Título:");
        escribir(cs, NORMAL, 10, 305, 205, f.titulo1());
        if (f.titulo2() != null) escribir(cs, NORMAL, 10, 305, 217, f.titulo2());
        escribir(cs, NEGRITA, 10, 305, 270, "Código: " + f.codigo());
        escribir(cs, NEGRITA, 10, 305, 306, "Edición: " + f.edicion());
        escribir(cs, NEGRITA, 9, 305, 349, "Fecha de edición: " + f.fecha());
        escribir(cs, NEGRITA, 10, 305, 387, "Especialidad: " + f.especialidad());
        escribir(cs, NEGRITA, 10, 306, 424, "Deroga a: " + f.deroga());
        escribir(cs, NEGRITA, 10, 84, 485, "Objeto del documento:");
        float y = 518;
        for (String linea : f.objeto()) {
            escribir(cs, NORMAL, 9, 84, y, linea);
            y += 11.5f;
        }
    }

    /** {@code y} medida desde arriba, como en el volcado. */
    private static void escribir(PDPageContentStream cs, PDType1Font fuente, float tam, float x, float y, String t)
            throws IOException {
        cs.beginText();
        cs.setFont(fuente, tam);
        cs.newLineAtOffset(x, ALTO - y);
        cs.showText(t);
        cs.endText();
    }

    /** Permite generar el PDF de ejemplo a mano: {@code PdfDeEjemplo datos/ejemplo.pdf}. */
    public static void main(String[] args) throws IOException {
        crear(Path.of(args.length > 0 ? args[0] : "datos/ejemplo.pdf"));
    }
}
