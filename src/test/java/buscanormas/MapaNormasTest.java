package buscanormas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import buscanormas.MapaNormas.Datos;
import buscanormas.MapaNormas.Entrada;

class MapaNormasTest {

    @TempDir
    Path tmp;

    private static final Entrada CANALETAS = new Entrada("0b0003ea801f061e", "ET 03.305.001.4_ED1M1M2",
            "Canaletas prefabricadas de hormigón para cables. (ED1M1M2).", "ED1M1M2", "24/01/2024",
            "090003ea801f1853", "ET03.305.001.4_ED1M1M2.pdf");

    private static Norma norma(String codigo) {
        return new Norma(codigo, "", "", "", "", "", "", Norma.tipoDe(codigo), 1);
    }

    @Test
    void seGuardaYSeLeeIgual() throws IOException {
        Datos d = new Datos("2026-10-19T10:00:00Z", Map.of("ET 03.305.001.4", List.of(CANALETAS)));
        Path f = tmp.resolve("mapa.json");
        MapaNormas.escribir(d, f);
        try (InputStream in = Files.newInputStream(f)) {
            assertEquals(d, MapaNormas.leer(in));
        }
    }

    @Test
    void usaElMapaMasReciente() throws IOException {
        Path f = tmp.resolve("mapa.json");
        MapaNormas.escribir(new Datos("9999-01-01T00:00:00Z", Map.of("X 1", List.of(CANALETAS))), f);
        MapaNormas mapa = new MapaNormas();
        mapa.cargarLocal(f);
        assertEquals("9999-01-01T00:00:00Z", mapa.generado());
        assertEquals(List.of(CANALETAS), mapa.buscar("X 1"));
        assertEquals(List.of(), mapa.buscar("NO EXISTE"));
    }

    @Test
    void ignoraMapasVacios() throws IOException {
        Path f = tmp.resolve("vacio.json");
        MapaNormas.escribir(new Datos("9999-01-01T00:00:00Z", Map.of()), f);
        MapaNormas mapa = new MapaNormas();
        mapa.cargarLocal(f);
        assertFalse(mapa.generado().startsWith("9999"));
    }

    @Test
    void generarOmiteLosCodigosQueFallanYNoRepite() {
        List<String> consultados = new java.util.ArrayList<>();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Datos d = MapaNormas.generar(List.of(norma("ET 03.305.001.4"), norma("NAP 9"), norma("ET 03.305.001.4")),
                codigo -> {
                    consultados.add(codigo);
                    if (codigo.equals("NAP 9")) throw new IOException("HTTP 500");
                    return List.of(CANALETAS);
                }, 0, new PrintStream(log));
        assertEquals(List.of("ET 03.305.001.4", "NAP 9"), consultados);
        assertEquals(Map.of("ET 03.305.001.4", List.of(CANALETAS)), d.normas());
        assertTrue(log.toString().contains("Sin consultar por error: [NAP 9]"));
        assertFalse(d.generado().isEmpty());
    }

    @Test
    void comparaLaFechaDeLaFichaConLaDelMapa() {
        assertTrue(App.mismaFecha("Enero de 2024", "24/01/2024"));
        assertTrue(App.mismaFecha("Septiembre de 2019", "01/09/2019"));
        assertFalse(App.mismaFecha("Junio de 2026", "24/01/2024"), "edición más nueva que el mapa");
        assertTrue(App.mismaFecha("", "24/01/2024"), "si no se entiende, no se avisa");
    }

    @Test
    void fechaCorta() {
        assertEquals("19/10/2026", App.fechaCorta("2026-10-19T10:00:00Z"));
    }
}
