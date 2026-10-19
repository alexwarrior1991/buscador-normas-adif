package buscanormas;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una ficha del catálogo. Los campos que la ficha no trae quedan en cadena vacía
 * (nunca null) para simplificar el índice y la serialización a JSON.
 *
 * @param tipo   prefijo del código ("ET", "NAP", "NAV"...), útil para filtrar
 * @param pagina página del PDF del catálogo donde está la ficha
 */
public record Norma(
        String codigo,
        String titulo,
        String edicion,
        String fecha,
        String especialidad,
        String deroga,
        String objeto,
        String tipo,
        int pagina) {

    private static final Pattern PREFIJO = Pattern.compile("\\p{L}[\\p{L}.]*");

    /** "ET 03.305.001.4" → "ET", "N.F.I. ANDENES 001" → "NFI", "03.432.800 (NAS 800)" → "NAS". */
    static String tipoDe(String codigo) {
        Matcher m = PREFIJO.matcher(codigo);
        return m.find() ? m.group().replace(".", "").toUpperCase(Locale.ROOT) : "";
    }
}
