package buscanormas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * Configuración común de las peticiones a adif.es (catálogo y "Abrir norma").
 *
 * <p>En algunas redes la descarga funcionaba en el navegador pero Java recibía HTTP 403: Java no
 * usa por defecto el proxy de Windows, y una petición sin las cabeceras de un navegador puede ser
 * rechazada por filtros anti-bots. Por eso se usa el proxy del sistema y cabeceras de navegador.
 */
final class ClienteHttp {

    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    private ClienteHttp() {
    }

    /**
     * Usa el proxy configurado en el sistema (en Windows, el mismo que el navegador, incluida la
     * configuración automática). Hay que llamarlo antes de crear ningún cliente HTTP. No pisa un
     * proxy indicado a mano con {@code -Dhttps.proxyHost} ni {@code -Djava.net.useSystemProxies}.
     */
    static void usarProxyDelSistema() {
        if (System.getProperty("https.proxyHost") == null && System.getProperty("java.net.useSystemProxies") == null) {
            System.setProperty("java.net.useSystemProxies", "true");
        }
    }

    static HttpClient.Builder cliente() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20));
    }

    static HttpRequest.Builder peticion(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/pdf,application/json,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9")
                .timeout(Duration.ofMinutes(2));
    }
}
