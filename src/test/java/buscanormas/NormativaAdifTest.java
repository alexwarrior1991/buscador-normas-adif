package buscanormas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import buscanormas.NormativaAdif.Anexo;
import buscanormas.NormativaAdif.Documento;

/** Sin red: respuestas reales de la web de ADIF (octubre 2026) guardadas como texto. */
class NormativaAdifTest {

    private static final String GET_DOCUMENTOS = """
            {"data":[["0b0003ea801f061a","ET 03.305.001.4","Canaletas prefabricadas de hormigón para cables","1ª",\
            "01/07/2018","Derogado","/DN_NTE/01. NORMATIVA TÉCNICA Y BASE DE PRECIOS/02. NORMATIVA TÉCNICA DEROGADA"],\
            ["0b0003ea801f061b","ET 03.305.001.4_1M1","Canaletas prefabricadas de hormigón para cables. (1ª Edición+M1)",\
            "1ª+M1","01/01/2020","Derogado","/DN_NTE/..."],\
            ["0b0003ea801f061e","ET 03.305.001.4_ED1M1M2","Canaletas prefabricadas de hormigón para cables. (ED1M1M2).",\
            "ED1M1M2","24/01/2024","Vigente","/DN_NTE/01. NORMATIVA TÉCNICA Y BASE DE PRECIOS/01. NORMATIVA TÉCNICA VIGENTE"]],\
            "recordsTotal":3,"recordsFiltered":3,"draw":1,"error":null}""";

    private static final String GET_ANEXOS = """
            {"data":[["090003ea801f1853","ET03.305.001.4_ED1M1M2.pdf","pdf","nte_doc_anxo",null,"0b0003ea801f061e",null]],\
            "recordsTotal":1,"recordsFiltered":1,"draw":1,"error":null}""";

    private static Documento doc(String codigo, String estado) {
        return new Documento("id-" + codigo, codigo, "", "", "", estado);
    }

    private static List<String> elegidos(String codigoCatalogo, Documento... documentos) {
        return NormativaAdif.elegir(codigoCatalogo, List.of(documentos)).stream().map(Documento::codigo).toList();
    }

    @Test
    void leeLaRespuestaDeBusqueda() throws Exception {
        List<Documento> docs = NormativaAdif.leerDocumentos(GET_DOCUMENTOS);
        assertEquals(3, docs.size());
        assertEquals(new Documento("0b0003ea801f061e", "ET 03.305.001.4_ED1M1M2",
                "Canaletas prefabricadas de hormigón para cables. (ED1M1M2).", "ED1M1M2", "24/01/2024", "Vigente"),
                docs.get(2));
        assertEquals(List.of("0b0003ea801f061e"),
                NormativaAdif.elegir("ET 03.305.001.4", docs).stream().map(Documento::id).toList());
    }

    @Test
    void leeLosAnexos() throws Exception {
        assertEquals(List.of(new Anexo("090003ea801f1853", "ET03.305.001.4_ED1M1M2.pdf", "pdf")),
                NormativaAdif.leerAnexos(GET_ANEXOS));
    }

    @Test
    void eligeLaVigenteAunqueLleveSufijoDeVersion() {
        assertEquals(List.of("NAV 3-3-2.1_ED4"), elegidos("NAV 3-3-2.1",
                doc("NAV 3-3-2.1", "Derogado"), doc("NAV 3-3-2.1_ED3", "Derogado"), doc("NAV 3-3-2.1_ED4", "Vigente")));
        assertEquals(List.of("NRS 7-3-0.0."), elegidos("NRS 7-3-0.0 (NAS 730)", doc("NRS 7-3-0.0.", "Vigente")));
    }

    @Test
    void noConfundeCodigosQueEmpiezanIgual() {
        assertEquals(List.of("NAE 107"), elegidos("NAE 107", doc("NAE 107", "Vigente"), doc("NAE 1070", "Vigente")));
    }

    @Test
    void ignoraElCodigoAlternativoEntreParentesis() {
        assertEquals(List.of("IT 17"), elegidos("IT 17 (NAS 117)", doc("IT 17", "Vigente")));
        assertEquals(List.of("03.432.800"), elegidos("03.432.800 (NAS 800)", doc("03.432.800", "Vigente")));
    }

    @Test
    void codigosConGuionBajoPropio() {
        assertEquals(List.of("ET 03.359.109.0_01.10"),
                elegidos("ET 03.359.109.0_01.10", doc("ET 03.359.109.0_01.10", "Vigente")));
    }

    @Test
    void comparaSinSignosNiEspacios() {
        assertEquals(List.of("N.F.I. ANDENES 001"), elegidos("N.F.I. AN DENES 001",
                doc("N.F.I. ANDENES 001", "Vigente"), doc("N.F.I. VIA 001", "Derogado")));
    }

    @Test
    void coleccionesDevuelvenTodosSusDocumentos() {
        assertEquals(List.of("PAV 01_01.01.000_ED2M1", "PAV 01_01.01.001_ED1M1M2"), elegidos("PAV",
                doc("Indice_PAV_ED10", "Vigente"), doc("PAV 01_01.01.001_ED1M1M2", "Vigente"),
                doc("PAV 01_01.01.000_ED2M1", "Vigente"), doc("PAV 01_99.00.000", "Derogado")));
    }

    @Test
    void sinVigentesNoDevuelveNada() {
        assertEquals(List.of(), elegidos("NAP 9-9-9.9", doc("NAP 9-9-9.9", "Derogado")));
    }

    @Test
    void nombreDeFicheroSeguro() {
        assertEquals("ET03.305.001.4_ED1M1M2.pdf", App.nombreSeguro("ET03.305.001.4_ED1M1M2.pdf"));
        assertEquals("_.._etc_passwd.pdf", App.nombreSeguro("../../etc/passwd"), "sin barras: no sale de su carpeta");
        assertEquals("norma.pdf", App.nombreSeguro(""));
    }

    @Test
    void codigoParaElBuscadorOficial() {
        assertEquals("IT 17", App.codigoParaBuscar("IT 17 (NAS 117)"));
        assertEquals("ET 03.305.001.4", App.codigoParaBuscar("ET 03.305.001.4"));
    }

    @Test
    void urlDeDescargaDirecta() {
        assertEquals(NormativaAdif.WEB + "/action/DocumentacionController/descargarDocumentoAnexo?idDoc=090003ea801f1853",
                NormativaAdif.urlDescarga("090003ea801f1853"));
    }
}
