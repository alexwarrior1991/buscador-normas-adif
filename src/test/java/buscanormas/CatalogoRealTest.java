package buscanormas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Comprobaciones sobre el catálogo real (20ª edición, junio 2026). No se incluye en el repositorio:
 * el test solo corre si está descargado en datos/CatalogoNormativaTecnica.pdf. Con otra edición
 * pueden cambiar los recuentos, pero no deberían aparecer fichas con campos vacíos.
 */
class CatalogoRealTest {

    private static final Path PDF = Path.of("datos", "CatalogoNormativaTecnica.pdf");

    @Test
    void todasLasFichasTienenSusCampos() throws Exception {
        assumeTrue(Files.exists(PDF), "catálogo real no descargado");
        List<Norma> normas = CatalogoPdf.leer(PDF);

        assertTrue(normas.size() > 300, "fichas: " + normas.size());
        for (Norma n : normas) {
            String donde = "página " + n.pagina() + ": " + n;
            assertTrue(!n.codigo().isEmpty() && !n.tipo().isEmpty(), donde);
            assertTrue(!n.titulo().isEmpty() && !n.especialidad().isEmpty() && !n.objeto().isEmpty(), donde);
            assertTrue(!n.objeto().contains("DERECHOS RESERVADOS"), donde);
        }
        Norma canaletas = normas.stream().filter(n -> n.codigo().equals("ET 03.305.001.4")).findFirst().orElseThrow();
        assertEquals("Canaletas prefabricadas de hormigón para cables.", canaletas.titulo());
        assertEquals("Plataforma", canaletas.especialidad());
    }
}
