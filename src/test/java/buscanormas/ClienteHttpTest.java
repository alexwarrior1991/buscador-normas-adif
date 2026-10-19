package buscanormas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClienteHttpTest {

    private String proxyHost;
    private String sistema;

    @BeforeEach
    void guardar() {
        proxyHost = System.getProperty("https.proxyHost");
        sistema = System.getProperty("java.net.useSystemProxies");
    }

    @AfterEach
    void restaurar() {
        restaurar("https.proxyHost", proxyHost);
        restaurar("java.net.useSystemProxies", sistema);
    }

    private static void restaurar(String clave, String valor) {
        if (valor == null) System.clearProperty(clave);
        else System.setProperty(clave, valor);
    }

    @Test
    void lasPeticionesLlevanCabecerasDeNavegador() {
        HttpHeaders h = ClienteHttp.peticion("https://www.adif.es/x.pdf").build().headers();
        assertTrue(h.firstValue("User-Agent").orElseThrow().contains("Chrome/"));
        assertTrue(h.firstValue("Accept").orElseThrow().contains("application/pdf"));
        assertEquals("es-ES,es;q=0.9", h.firstValue("Accept-Language").orElseThrow());
    }

    @Test
    void usaElProxyDelSistemaPorDefecto() {
        System.clearProperty("https.proxyHost");
        System.clearProperty("java.net.useSystemProxies");
        ClienteHttp.usarProxyDelSistema();
        assertEquals("true", System.getProperty("java.net.useSystemProxies"));
    }

    @Test
    void respetaUnProxyIndicadoAMano() {
        System.setProperty("https.proxyHost", "proxy.empresa.local");
        System.clearProperty("java.net.useSystemProxies");
        ClienteHttp.usarProxyDelSistema();
        assertNull(System.getProperty("java.net.useSystemProxies"));
    }
}
