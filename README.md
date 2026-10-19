# Buscador de normas ADIF

Aplicación local para explorar y buscar rápidamente en el
[Catálogo de Normativa Técnica de ADIF](https://www.adif.es/documents/20124/3371563/CatalogoNormativaTecnica.pdf/).

> Herramienta personal **no oficial**, sin relación con ADIF. Los datos salen del catálogo público
> que ADIF publica en su web; para cualquier uso formal, consulta siempre la fuente oficial.

- **Backend:** Java 25 con [Javalin](https://javalin.io) (servidor web), [PDFBox](https://pdfbox.apache.org) (lectura del PDF) y [Lucene](https://lucene.apache.org) (búsqueda).
- **Interfaz:** React (Vite, JavaScript), compilada dentro del `.jar`.

Para usarla solo hace falta **Java 25** o superior (Windows o Linux); Node no es necesario.
El jar está compilado para Java 25, así que con una versión anterior no arranca.

## Uso

```bash
java -jar normas-adif.jar
# abre http://localhost:8080
```

La primera vez descarga el catálogo en `datos/CatalogoNormativaTecnica.pdf`. Después usa ese
fichero; el botón **Actualizar catálogo** (o el comando `actualizar`) lo vuelve a descargar.
Si la descarga falla, se conserva el PDF anterior.

| Comando | Qué hace |
|---|---|
| `java -jar normas-adif.jar [servir]` | Arranca el buscador |
| `java -jar normas-adif.jar actualizar` | Descarga de nuevo el PDF |
| `java -jar normas-adif.jar volcar catalogo.pdf > volcado.txt` | Muestra lo que se extrae del PDF (para ajustar el lector) |

Opciones: `--pdf ruta.pdf` (usar un PDF ya descargado), `--url ...`, `--puerto 8080`.

### Si la descarga del catálogo da HTTP 403

En algunas redes (sobre todo de empresa) el navegador descarga el catálogo pero Java recibe un
`HTTP 403`. La aplicación ya usa el proxy configurado en el sistema (en Windows, el mismo que el
navegador) y cabeceras de navegador. Si aun así la red o ADIF rechazan la descarga:

1. Descarga el PDF con el navegador desde
   <https://www.adif.es/documents/20124/3371563/CatalogoNormativaTecnica.pdf/>.
2. En la página del buscador, pulsa **Cargar PDF…** y elige el fichero descargado. Se lee en unos
   segundos y queda guardado para los siguientes arranques. Lo mismo sirve para cargar una edición
   nueva del catálogo.

También puedes guardarlo a mano como `datos\CatalogoNormativaTecnica.pdf` dentro de la carpeta desde
la que lanzas `java -jar` (la interfaz muestra la ruta exacta), o arrancar con `--pdf ruta\al\catalogo.pdf`.
Si el PDF ya existe, no se intenta descargar.

Para forzar un proxy concreto:
`java -Dhttps.proxyHost=proxy.miempresa.es -Dhttps.proxyPort=8080 -jar normas-adif.jar`.

### En la interfaz

- Se busca mientras se escribe, por **código** (`nap 0-1` encuentra `NAP 0-1-0.0`; `nas 117`
  encuentra `IT 17 (NAS 117)`) o por **palabras** del título, el objeto, la especialidad o las
  normas que deroga (`NRS 03.432.310` muestra qué normas la derogan). No distingue acentos ni
  mayúsculas, trata igual singular y plural y tolera una errata. Lo que está en el título puntúa más.
- Filtros por especialidad y por tipo de documento (ET, NAG, NAV, NAP...).
- **Abrir norma** abre el PDF de la norma (versión vigente) desde la web oficial de ADIF.
- **Ver ficha** abre la página de esa norma en el catálogo, como un PDF de una sola página.
- **Cargar PDF…** carga un catálogo descargado con el navegador (si Java no puede descargarlo).
- `/` vuelve al buscador y `Esc` lo vacía. Clic en un código para copiarlo.
- La búsqueda queda en la URL (`?q=...`), así que se puede guardar en favoritos o compartir.

## Cómo se lee el catálogo

El catálogo (20ª edición, junio 2026: 400 páginas, 388 fichas) tiene **una ficha por página**:

- **Columna derecha:** campos `Título`, `Código`, `Edición`, `Fecha de edición`, `Especialidad`
  y `Deroga a`.
- **Izquierda:** una miniatura de la portada de la norma, cuyo texto sale como basura y se descarta.
- **Debajo:** el `Objeto del documento`.

`CatalogoPdf` lee cada zona por separado, se salta las páginas que no son fichas (portada y
separadores de especialidad) y corrige algunos artefactos de la fuente del PDF: `1~` → `1ª`,
`Ml` → `M1`, espacios dentro de los códigos...

El catálogo no trae el estado (solo incluye normas en vigor) ni enlaces al PDF de cada norma;
para eso está "Abrir norma" (siguiente apartado).

Si una edición futura cambia la maquetación, `volcar` muestra lo que se extrae de cada página.
`CatalogoRealTest` comprueba que todas las fichas tengan sus campos; se ejecuta solo si el catálogo
está en `datos/`.

El catálogo indica que no puede ser distribuido por terceros. Por eso el PDF no se incluye en el
repositorio (`datos/` está en `.gitignore`): cada instalación lo descarga de adif.es.

## Abrir la norma

El catálogo solo tiene la ficha resumen; los documentos están en la
[web de normativa técnica de ADIF](https://normativatecnica.adif.es/ntw/). Al pulsar
**Abrir norma**, el backend (`NormativaAdif.java`) hace lo mismo que esa web cuando se busca y se
pulsa "Ver documento", llamando a su API interna (sin Selenium ni navegador):

1. `GET  /ntw/views/busqueda/body-busqueda.jsp`: cookie de sesión y token CSRF.
2. `POST /ntw/action/BusquedaController/getDocumentos`: versiones de esa norma por código; se
   queda con la **vigente** (por ejemplo, `NAV 3-3-2.1` → `NAV 3-3-2.1_ED4`).
3. `GET  /ntw/action/DocumentacionController/getDocumentosAnexos`: el PDF de esa versión.
4. `GET  /ntw/action/DocumentacionController/descargarDocumentoAnexo`: lo descarga.

- El PDF se guarda en `datos/normas/` y la próxima vez se abre al instante y sin conexión.
- Si un código tiene varios documentos vigentes (como `PAV`, la colección de planos tipo de vía),
  se muestra la lista.
- Solo se consulta lo que se abre: no hay descargas masivas.
- Necesita internet. Si ADIF cambia su web, este botón podría dejar de funcionar, pero el
  buscador seguiría funcionando porque solo depende del catálogo.

## Desarrollo

Para compilar hace falta un **JDK 25** y Maven 3.9 (si el JDK es anterior, Maven lo avisa).

```bash
mvn package                       # compila React + Java, pasa los tests y genera target/normas-adif.jar
mvn package -Dskip.npm -Dskip.installnodenpm   # solo Java (reutiliza la interfaz ya compilada)

# Interfaz con recarga en caliente:
java -jar target/normas-adif.jar                             # backend en :8080
cd frontend && npm install && npm run dev                    # interfaz en :5173 (redirige /api, /ficha y /norma a :8080)
```

PDF de ejemplo (inventado, imita la maquetación real; es el que usan los tests):

```bash
java -cp target/test-classes:target/normas-adif.jar buscanormas.PdfDeEjemplo datos/ejemplo.pdf
```

(En Windows, usa `;` en lugar de `:` en el `-cp`.)

El aviso de Lucene `Java vector incubator module is not readable` es inofensivo.

```
src/main/java/buscanormas/
  App.java          arranque, comandos, API REST y descarga del PDF
  CatalogoPdf.java  PDF del catálogo → lista de normas (una ficha por página)
  Indice.java       índice Lucene en memoria y consultas
  Norma.java        una ficha del catálogo
  NormativaAdif.java cliente de la web oficial para "Abrir norma"
  ClienteHttp.java  proxy del sistema y cabeceras comunes de las peticiones a adif.es
frontend/src/
  App.jsx           buscador y filtros
  Resultados.jsx    lista de normas
  resaltar.jsx      resaltado sin distinguir acentos
  api.js            llamadas a /api
```
