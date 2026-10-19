// Llamadas a la API del backend Java (buscanormas.App).
//
// Cada norma que devuelve /api/buscar tiene estos campos (ver Norma.java):
//   codigo, titulo, edicion, fecha, especialidad, deroga, objeto, tipo, pagina

export const LIMITE = 200;

/** Página del catálogo con la ficha de la norma, como PDF de una sola página. */
export const urlFicha = (pagina) => `/ficha/${pagina}`;

/**
 * PDF de la norma en la web oficial de ADIF. El backend lo busca por código y lo descarga
 * (o lo sirve de datos/normas/ si ya se abrió antes); si hay varios documentos, muestra la lista.
 */
export const urlNorma = (codigo) => `/norma?codigo=${encodeURIComponent(codigo)}`;

export const BUSCADOR_OFICIAL = "https://normativatecnica.adif.es/ntw/";

async function json(respuesta) {
  if (!respuesta.ok) {
    const cuerpo = await respuesta.json().catch(() => ({}));
    throw new Error(cuerpo.error ?? `Error ${respuesta.status}`);
  }
  return respuesta.json();
}

/** filtros = { q, especialidad, tipo } → { total, resultados: [norma, ...] } */
export function buscar(filtros, signal) {
  const p = new URLSearchParams({
    q: filtros.q,
    especialidad: filtros.especialidad,
    tipo: filtros.tipo,
    limite: String(LIMITE),
  });
  return fetch(`/api/buscar?${p}`, { signal }).then(json);
}

/** → { total, especialidades: [{ valor, cantidad }], tipos: [{ valor, cantidad }] } */
export const obtenerFacetas = () => fetch("/api/facetas").then(json);

/**
 * → { disponible, motivo, buscador, mapa, normasMapa }. Si disponible es false, Java no llega a la
 * web de ADIF desde esta red y "Abrir norma" usa el mapa de normas (generado el día `mapa`).
 */
export const obtenerAccesoAdif = () => fetch("/api/adif").then(json);

/** "2026-10-19T09:15:00Z" → "19/10/2026". */
export const fechaCorta = (instante) =>
  instante && instante.length >= 10 ? `${instante.slice(8, 10)}/${instante.slice(5, 7)}/${instante.slice(0, 4)}` : "";

/** → { pdf, url, normas, cargado, error } */
export const obtenerInfo = () => fetch("/api/info").then(json);

export const actualizarCatalogo = () => fetch("/api/actualizar", { method: "POST" }).then(json);

/** Sube el PDF del catálogo descargado con el navegador (para redes donde Java no puede). → info */
export const cargarCatalogo = (fichero) =>
  fetch("/api/catalogo", { method: "POST", headers: { "Content-Type": "application/pdf" }, body: fichero }).then(json);
