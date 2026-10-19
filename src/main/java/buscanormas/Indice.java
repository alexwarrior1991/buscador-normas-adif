package buscanormas;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.es.SpanishLightStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;

/**
 * Índice Lucene en memoria. Es inmutable: para actualizar el catálogo se crea uno nuevo
 * y se sustituye la referencia, así las búsquedas en curso no se ven afectadas.
 */
public final class Indice {

    public record Resultado(long total, List<Norma> resultados) {
    }

    public record Faceta(String valor, long cantidad) {
    }

    public record Facetas(int total, List<Faceta> especialidades, List<Faceta> tipos) {
    }

    private static final Analyzer ANALIZADOR = new AnalizadorEspanol();
    private static final Pattern ENTRE_PARENTESIS = Pattern.compile("\\(([^)]+)\\)");

    private final IndexSearcher buscador;
    private final Facetas facetas;

    public Indice(List<Norma> normas) {
        try {
            ByteBuffersDirectory dir = new ByteBuffersDirectory();
            try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(ANALIZADOR))) {
                for (int i = 0; i < normas.size(); i++) {
                    w.addDocument(documento(normas.get(i), i));
                }
            }
            buscador = new IndexSearcher(DirectoryReader.open(dir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        facetas = new Facetas(normas.size(),
                contar(normas.stream().map(Norma::especialidad).toList()),
                contar(normas.stream().map(Norma::tipo).toList()));
    }

    public Facetas facetas() {
        return facetas;
    }

    /** Sin texto se devuelven todas en orden de catálogo; con texto, por relevancia. */
    public Resultado buscar(String texto, String especialidad, String tipo, int limite) throws IOException {
        String t = texto == null ? "" : texto;
        Query porTexto = consultaDeTexto(t);
        Query consulta = filtrar(porTexto, especialidad, tipo);
        boolean ordenCatalogo = porTexto == null;

        // Si lo escrito parece un código ("nap 0-1", "ET 03.365") y hay códigos que empiezan así,
        // solo esos: si no, los números sueltos ("0", "1") encajarían en casi cualquier ficha.
        String clave = claveCodigo(t);
        if (clave.matches(".*\\d.*")) {
            Query porCodigo = filtrar(new PrefixQuery(new Term("codigo_clave", clave)), especialidad, tipo);
            if (buscador.count(porCodigo) > 0) {
                consulta = porCodigo;
                ordenCatalogo = true;
            }
        }

        TopDocs docs = ordenCatalogo
                ? buscador.search(consulta, limite, new Sort(new SortField("orden", SortField.Type.INT)))
                : buscador.search(consulta, limite);
        StoredFields almacen = buscador.storedFields();
        List<Norma> lista = new ArrayList<>();
        for (ScoreDoc sd : docs.scoreDocs) {
            lista.add(norma(almacen.document(sd.doc)));
        }
        return new Resultado(buscador.count(consulta), lista);
    }

    private static Query filtrar(Query consulta, String especialidad, String tipo) {
        BooleanQuery.Builder q = new BooleanQuery.Builder();
        q.add(consulta == null ? new MatchAllDocsQuery() : consulta, Occur.MUST);
        if (especialidad != null && !especialidad.isBlank()) {
            q.add(new TermQuery(new Term("especialidad", especialidad)), Occur.FILTER);
        }
        if (tipo != null && !tipo.isBlank()) q.add(new TermQuery(new Term("tipo", tipo)), Occur.FILTER);
        return q.build();
    }

    /**
     * El texto puede ser un código ("nap 0-1") o palabras ("canaletas hormigon").
     * Código: prefijo sobre el código sin signos. Palabras: todas deben aparecer en la ficha
     * (título, objeto, especialidad, normas derogadas...), admitiendo prefijo (búsqueda mientras
     * se escribe) y una errata en palabras de 5 letras o más; si están en el título puntúa más.
     */
    private static Query consultaDeTexto(String texto) {
        String clave = claveCodigo(texto);
        List<String> terminos = analizar(texto);
        if (clave.isEmpty() && terminos.isEmpty()) return null;

        BooleanQuery.Builder q = new BooleanQuery.Builder();
        if (!clave.isEmpty()) {
            q.add(new BoostQuery(new PrefixQuery(new Term("codigo_clave", clave)), 20f), Occur.SHOULD);
        }
        if (!terminos.isEmpty()) {
            BooleanQuery.Builder todas = new BooleanQuery.Builder();
            for (String t : terminos) {
                BooleanQuery.Builder una = new BooleanQuery.Builder();
                una.add(new BoostQuery(new TermQuery(new Term("titulo", t)), 4f), Occur.SHOULD);
                una.add(new BoostQuery(new PrefixQuery(new Term("titulo", t)), 2f), Occur.SHOULD);
                una.add(new TermQuery(new Term("resto", t)), Occur.SHOULD);
                una.add(new BoostQuery(new PrefixQuery(new Term("resto", t)), 0.5f), Occur.SHOULD);
                if (t.length() >= 5) {
                    una.add(new FuzzyQuery(new Term("titulo", t), 1), Occur.SHOULD);
                    una.add(new BoostQuery(new FuzzyQuery(new Term("resto", t), 1), 0.3f), Occur.SHOULD);
                }
                todas.add(una.build(), Occur.MUST);
            }
            q.add(todas.build(), Occur.SHOULD);
        }
        return q.build();
    }

    private static Document documento(Norma n, int orden) {
        Document d = new Document();
        d.add(new StoredField("codigo", n.codigo()));
        // "IT 17 (NAS 117)": se encuentra tanto por "it 17" como por "nas 117".
        d.add(new StringField("codigo_clave", claveCodigo(n.codigo()), Field.Store.NO));
        Matcher alternativo = ENTRE_PARENTESIS.matcher(n.codigo());
        while (alternativo.find()) {
            d.add(new StringField("codigo_clave", claveCodigo(alternativo.group(1)), Field.Store.NO));
        }
        d.add(new TextField("titulo", n.titulo(), Field.Store.YES));
        d.add(new TextField("resto", String.join(" ", n.codigo(), n.especialidad(), n.edicion(), n.fecha(),
                n.deroga(), n.objeto()), Field.Store.NO));
        d.add(new StoredField("edicion", n.edicion()));
        d.add(new StoredField("fecha", n.fecha()));
        d.add(new StringField("especialidad", n.especialidad(), Field.Store.YES));
        d.add(new StoredField("deroga", n.deroga()));
        d.add(new StoredField("objeto", n.objeto()));
        d.add(new StringField("tipo", n.tipo(), Field.Store.YES));
        d.add(new StoredField("pagina", n.pagina()));
        d.add(new NumericDocValuesField("orden", orden));
        return d;
    }

    private static Norma norma(Document d) {
        return new Norma(d.get("codigo"), d.get("titulo"), d.get("edicion"), d.get("fecha"), d.get("especialidad"),
                d.get("deroga"), d.get("objeto"), d.get("tipo"), d.getField("pagina").numericValue().intValue());
    }

    /** "NAP 0-1-0.0" y "nap 0-1" comparten prefijo "nap0100" / "nap01". */
    static String claveCodigo(String s) {
        return CatalogoPdf.sinAcentos(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static List<String> analizar(String texto) {
        List<String> salida = new ArrayList<>();
        try (TokenStream ts = ANALIZADOR.tokenStream("texto", texto)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) salida.add(term.toString());
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return salida;
    }

    private static List<Faceta> contar(List<String> valores) {
        Map<String, Long> cuenta = new TreeMap<>();
        for (String v : valores) {
            if (!v.isBlank()) cuenta.merge(v, 1L, Long::sum);
        }
        return cuenta.entrySet().stream().map(e -> new Faceta(e.getKey(), e.getValue())).toList();
    }

    /** Minúsculas, sin acentos y con plurales/género reducidos: "Señalizaciones" ≈ "senalizacion". */
    private static final class AnalizadorEspanol extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String campo) {
            Tokenizer t = new StandardTokenizer();
            TokenStream s = new SpanishLightStemFilter(new ASCIIFoldingFilter(new LowerCaseFilter(t)));
            return new TokenStreamComponents(t, s);
        }

        @Override
        protected TokenStream normalize(String campo, TokenStream in) {
            return new ASCIIFoldingFilter(new LowerCaseFilter(in));
        }
    }
}
