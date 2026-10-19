package buscanormas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

class IndiceTest {

    private static Norma norma(String codigo, String titulo, String especialidad, String deroga, String objeto) {
        return new Norma(codigo, titulo, "1ª Edición", "Enero de 2020", especialidad, deroga, objeto,
                Norma.tipoDe(codigo), 1);
    }

    private final Indice indice = new Indice(List.of(
            norma("ET 03.305.001.4", "Canaletas prefabricadas de hormigón para cables.", "Plataforma",
                    "Deroga parcialmente la NRS 03.432.310", "Describe las canaletas y su recepción."),
            norma("NAV 3-4-0.0", "Carriles. Soldadura aluminotérmica.", "Vía", "", "Procedimiento de soldadura."),
            norma("NAV 3-4-0.1", "Soldadura eléctrica de carriles.", "Vía", "", "Soldadura por chisporroteo."),
            norma("NAS 2-1-0.0", "Señalización lateral en líneas convencionales.", "Control, Mando y Señalización",
                    "", "Señales laterales."),
            norma("ET 03.365.010.3", "Señales luminosas LED.", "Control, Mando y Señalización", "",
                    "Requisitos de las pantallas de señal con hormigón.")));

    private List<String> codigos(String q) throws IOException {
        return codigos(q, null, null);
    }

    private List<String> codigos(String q, String especialidad, String tipo) throws IOException {
        return indice.buscar(q, especialidad, tipo, 50).resultados().stream().map(Norma::codigo).toList();
    }

    @Test
    void sinTextoDevuelveTodoEnOrdenDeCatalogo() throws IOException {
        assertEquals(List.of("ET 03.305.001.4", "NAV 3-4-0.0", "NAV 3-4-0.1", "NAS 2-1-0.0", "ET 03.365.010.3"),
                codigos(""));
    }

    @Test
    void ignoraAcentosYMayusculas() throws IOException {
        assertEquals(List.of("NAS 2-1-0.0"), codigos("SENALIZACION LATERAL"));
        assertEquals(List.of("NAV 3-4-0.0"), codigos("aluminotermica"));
    }

    @Test
    void buscaMientrasSeEscribe() throws IOException {
        assertEquals(List.of("ET 03.365.010.3"), codigos("señales lumin"));
        assertEquals(List.of("NAV 3-4-0.1"), codigos("soldadura electr"));
    }

    @Test
    void singularYPluralCoinciden() throws IOException {
        assertEquals(List.of("NAS 2-1-0.0"), codigos("linea convencional"));
        assertTrue(codigos("carril").containsAll(List.of("NAV 3-4-0.0", "NAV 3-4-0.1")));
    }

    @Test
    void toleraUnaErrata() throws IOException {
        assertEquals(List.of("ET 03.305.001.4"), codigos("prefabricadsa"));
    }

    @Test
    void buscaPorPrefijoDeCodigo() throws IOException {
        assertEquals(List.of("NAV 3-4-0.0", "NAV 3-4-0.1"), codigos("nav 3-4"));
        assertEquals(List.of("ET 03.305.001.4", "ET 03.365.010.3"), codigos("et 03"), "solo códigos, no '03' en textos");
        assertEquals("ET 03.365.010.3", codigos("ET 03.365").get(0));
        assertEquals(List.of("NAV 3-4-0.1"), codigos("NAV 3-4-0.1"));
    }

    @Test
    void encuentraPorCodigoAlternativo() throws IOException {
        Indice i = new Indice(List.of(norma("IT 17 (NAS 117)", "Instrucción técnica.", "Vía", "", "")));
        assertEquals(1, i.buscar("nas 117", null, null, 10).total());
        assertEquals(1, i.buscar("IT 17", null, null, 10).total());
    }

    @Test
    void elTituloPesaMasQueElObjeto() throws IOException {
        assertEquals(List.of("ET 03.305.001.4", "ET 03.365.010.3"), codigos("hormigon"));
    }

    @Test
    void encuentraQuienDerogaUnaNorma() throws IOException {
        assertEquals(List.of("ET 03.305.001.4"), codigos("NRS 03.432.310"));
    }

    @Test
    void filtraPorEspecialidadYTipo() throws IOException {
        assertEquals(List.of("NAV 3-4-0.0", "NAV 3-4-0.1"), codigos("", "Vía", null));
        assertEquals(List.of("ET 03.365.010.3"), codigos("", "Control, Mando y Señalización", "ET"));
        assertEquals(2, indice.buscar("", null, "ET", 50).total());
    }

    @Test
    void cuentaFacetas() {
        Indice.Facetas f = indice.facetas();
        assertEquals(5, f.total());
        assertEquals(List.of(new Indice.Faceta("Control, Mando y Señalización", 2),
                new Indice.Faceta("Plataforma", 1), new Indice.Faceta("Vía", 2)), f.especialidades());
        assertEquals(List.of(new Indice.Faceta("ET", 2), new Indice.Faceta("NAS", 1), new Indice.Faceta("NAV", 2)),
                f.tipos());
    }
}
