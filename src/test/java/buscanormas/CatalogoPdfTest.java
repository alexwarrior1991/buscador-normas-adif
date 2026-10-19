package buscanormas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lector contra el PDF inventado de {@link PdfDeEjemplo}, que imita la maquetación real. */
class CatalogoPdfTest {

    @TempDir
    static Path tmp;
    static List<Norma> normas;

    @BeforeAll
    static void leer() throws Exception {
        Path pdf = tmp.resolve("ejemplo.pdf");
        PdfDeEjemplo.crear(pdf);
        normas = CatalogoPdf.leer(pdf);
    }

    @Test
    void unaNormaPorFichaYSaltaLosSeparadores() {
        assertEquals(List.of("ET 03.305.001.4", "NAP 0-1-0.0", "NAV 3-4-0.0"),
                normas.stream().map(Norma::codigo).toList());
        assertEquals(List.of(1, 2, 4), normas.stream().map(Norma::pagina).toList());
    }

    @Test
    void leeLosCamposSinElTextoDeLaMiniatura() {
        Norma n = normas.get(0);
        assertEquals("Canaletas prefabricadas de hormigón para cables.", n.titulo());
        assertEquals("Enero de 2024", n.fecha());
        assertEquals("Plataforma", n.especialidad());
        assertEquals("ET", n.tipo());
    }

    @Test
    void corrigeArtefactosDeLaFuente() {
        assertEquals("1ª Edición + M1 + M2", normas.get(0).edicion());
        assertEquals("ET 03.305.001.4. 1ª Ed. + M1.", normas.get(0).deroga());
        assertEquals("1ª Edición", normas.get(1).edicion());
    }

    @Test
    void uneTitulosEnVariasLineas() {
        assertEquals("Nomenclatura de estructuras, túneles y obras de drenaje para proyectos de plataforma.",
                normas.get(1).titulo());
    }

    @Test
    void objetoSinAvisoLegalNiPie() {
        String objeto = normas.get(0).objeto();
        assertTrue(objeto.startsWith("La presente Especificación Técnica (ET) describe"), objeto);
        assertTrue(objeto.endsWith("tapas de canaletas."), objeto);
        assertFalse(objeto.contains("DERECHOS") || objeto.contains("Catálogo") || objeto.contains("aprobado"));
        assertEquals(2, objeto.split("\n").length, "un párrafo por frase terminada en punto");
    }

    @Test
    void derogaVacioCuandoNoHay() {
        assertEquals("", normas.get(2).deroga());
    }

    @Test
    void limpiaCodigos() {
        assertEquals("NAP 2-4-1.0", CatalogoPdf.codigo("NAP 2-4 -1.0"));
        assertEquals("ET 03.366.781.7", CatalogoPdf.codigo("ET 03.366. 781. 7"));
        assertEquals("NAP 1-2-2.0", CatalogoPdf.codigo("NAP 1-2-2 .O"));
        assertEquals("NRS 7-3-0.0 (NAS 730)", CatalogoPdf.codigo("NRS 7-3-0.0. (NAS 730)"));
        assertEquals("ET 03.359.109.0_01.10", CatalogoPdf.codigo("ET 03.359.109.0_01.10"));
        assertEquals("NFI", Norma.tipoDe("N.F.I. AN DENES 001"));
        assertEquals("NAS", Norma.tipoDe("03.432.800 (NAS 800)"));
    }
}
