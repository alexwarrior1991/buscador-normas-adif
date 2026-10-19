import { useEffect, useRef, useState } from "react";
import {
  actualizarCatalogo,
  BUSCADOR_OFICIAL,
  cargarCatalogo,
  buscar,
  LIMITE,
  obtenerFacetas,
  obtenerInfo,
} from "./api";
import { Resultados } from "./Resultados";

const ESPERA_MS = 150;

const SIN_FILTROS = { q: "", especialidad: "", tipo: "" };

/** Los filtros viven en la URL (?q=...&especialidad=...) para poder compartir o guardar una búsqueda. */
function filtrosDeLaUrl() {
  const p = new URLSearchParams(location.search);
  return { q: p.get("q") ?? "", especialidad: p.get("especialidad") ?? "", tipo: p.get("tipo") ?? "" };
}

export function App() {
  const [filtros, setFiltros] = useState(filtrosDeLaUrl);
  const [resultado, setResultado] = useState(null);
  const [facetas, setFacetas] = useState(null);
  const [info, setInfo] = useState(null);
  const [error, setError] = useState("");
  const [actualizando, setActualizando] = useState(false);
  const [version, setVersion] = useState(0); // fuerza recargar tras actualizar el catálogo
  const [subiendo, setSubiendo] = useState(false);
  const entrada = useRef(null);
  const selectorPdf = useRef(null);

  const cambiar = (cambios) => setFiltros((f) => ({ ...f, ...cambios }));

  useEffect(() => {
    obtenerFacetas().then(setFacetas).catch((e) => setError(e.message));
    obtenerInfo().then(setInfo).catch((e) => setError(e.message));
  }, [version]);

  // Búsqueda mientras se escribe: espera breve y cancela la petición anterior.
  useEffect(() => {
    const p = new URLSearchParams();
    Object.entries(filtros).forEach(([k, v]) => v && p.set(k, v));
    history.replaceState(null, "", p.size ? `?${p}` : location.pathname);

    const control = new AbortController();
    const t = setTimeout(() => {
      buscar(filtros, control.signal)
        .then((r) => {
          setResultado(r);
          setError("");
        })
        .catch((e) => e.name !== "AbortError" && setError(e.message));
    }, ESPERA_MS);
    return () => {
      clearTimeout(t);
      control.abort();
    };
  }, [filtros, version]);

  // "/" lleva al buscador desde cualquier sitio; Escape lo vacía.
  useEffect(() => {
    const tecla = (e) => {
      if (e.key === "/" && document.activeElement !== entrada.current) {
        e.preventDefault();
        entrada.current?.focus();
      }
    };
    addEventListener("keydown", tecla);
    return () => removeEventListener("keydown", tecla);
  }, []);

  const actualizar = async () => {
    setActualizando(true);
    try {
      setInfo(await actualizarCatalogo());
      setVersion((v) => v + 1);
    } catch (e) {
      setError(
        `No se pudo actualizar el catálogo: ${e.message} Puedes descargarlo con el navegador y usar «Cargar PDF…».`,
      );
    } finally {
      setActualizando(false);
    }
  };

  // Para redes donde Java no puede descargar el catálogo pero el navegador sí.
  const subirPdf = async (e) => {
    const fichero = e.target.files?.[0];
    e.target.value = "";
    if (!fichero) return;
    setSubiendo(true);
    try {
      setInfo(await cargarCatalogo(fichero));
      setError("");
      setVersion((v) => v + 1);
    } catch (err) {
      setError(`No se pudo cargar el PDF: ${err.message}`);
    } finally {
      setSubiendo(false);
    }
  };

  const textoCargar = subiendo ? "Leyendo el catálogo…" : "Cargar PDF…";

  const hayFiltros = filtros.q || filtros.especialidad || filtros.tipo;

  return (
    <div className="app">
      <header>
        <h1>Normas ADIF</h1>
        <div className="buscador">
          <input
            ref={entrada}
            autoFocus
            type="search"
            placeholder="Código o palabras (título, objeto, normas derogadas…)  — pulsa / para volver aquí"
            value={filtros.q}
            onChange={(e) => cambiar({ q: e.target.value })}
            onKeyDown={(e) => e.key === "Escape" && cambiar({ q: "" })}
            aria-label="Buscar normas"
          />
          <select
            value={filtros.especialidad}
            onChange={(e) => cambiar({ especialidad: e.target.value })}
            aria-label="Especialidad"
          >
            <option value="">Todas las especialidades</option>
            {facetas?.especialidades.map((m) => (
              <option key={m.valor} value={m.valor}>
                {m.valor} ({m.cantidad})
              </option>
            ))}
          </select>
          <select value={filtros.tipo} onChange={(e) => cambiar({ tipo: e.target.value })} aria-label="Tipo">
            <option value="">Todos los tipos</option>
            {facetas?.tipos.map((m) => (
              <option key={m.valor} value={m.valor}>
                {m.valor} ({m.cantidad})
              </option>
            ))}
          </select>
        </div>
      </header>

      {info?.error && (
        <div className="aviso">
          <p>
            <strong>No hay catálogo cargado.</strong> {info.error}
          </p>
          <ol>
            <li>
              Descarga el PDF con el navegador:{" "}
              <a href={info.url} target="_blank" rel="noreferrer">
                Catálogo de Normativa Técnica
              </a>
            </li>
            <li>
              Cárgalo aquí:{" "}
              <button onClick={() => selectorPdf.current?.click()} disabled={subiendo}>
                {subiendo ? textoCargar : "Seleccionar el PDF descargado…"}
              </button>
            </li>
          </ol>
          <p className="nota">
            También puedes guardarlo como <code>{info.pdf}</code> y volver a arrancar la aplicación.
          </p>
        </div>
      )}
      {error && <p className="aviso">{error}</p>}

      {resultado && (
        <p className="contador">
          {resultado.total === 1 ? "1 norma" : `${resultado.total.toLocaleString("es")} normas`}
          {resultado.total > LIMITE && ` · se muestran las ${LIMITE} primeras, afina la búsqueda`}
          {hayFiltros && (
            <button className="enlace" onClick={() => setFiltros(SIN_FILTROS)}>
              Quitar filtros
            </button>
          )}
        </p>
      )}

      {resultado && <Resultados normas={resultado.resultados} consulta={filtros.q} />}

      <footer>
        {info && !info.error && (
          <span>
            {info.normas} normas · cargado {info.cargado} · <code>{info.pdf}</code> ·{" "}
            <a href={BUSCADOR_OFICIAL} target="_blank" rel="noreferrer">
              Buscador oficial de ADIF
            </a>
          </span>
        )}
        <div className="botones">
          <button onClick={() => selectorPdf.current?.click()} disabled={subiendo} title="PDF del catálogo descargado con el navegador">
            {textoCargar}
          </button>
          <button onClick={actualizar} disabled={actualizando}>
            {actualizando ? "Descargando…" : "Actualizar catálogo"}
          </button>
        </div>
        <input ref={selectorPdf} type="file" accept="application/pdf,.pdf" hidden onChange={subirPdf} />
      </footer>
    </div>
  );
}
