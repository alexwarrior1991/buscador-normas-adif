const sinAcentos = (s) => s.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

/** Palabras de la búsqueda (2 letras o más), sin acentos y en minúsculas. */
export function terminos(consulta) {
  return sinAcentos(consulta)
    .split(/[^\p{L}\p{N}]+/u)
    .filter((t) => t.length >= 2);
}

/**
 * Fragmento de `texto` de unos `largo` caracteres: el principio, o si alguna palabra buscada
 * aparece más adelante, la zona alrededor de la primera coincidencia.
 */
export function extracto(texto, consulta, largo) {
  if (texto.length <= largo) return texto;
  const normal = sinAcentos(texto); // en español conserva las posiciones: "ñ" → "n"
  const posiciones = terminos(consulta)
    .map((p) => normal.indexOf(p))
    .filter((i) => i >= 0);
  const primera = posiciones.length ? Math.min(...posiciones) : 0;
  const inicio = primera + 20 > largo ? texto.lastIndexOf(" ", primera - largo / 3) + 1 : 0;
  const fin = texto.indexOf(" ", inicio + largo);
  return (inicio > 0 ? "…" : "") + texto.slice(inicio, fin < 0 ? undefined : fin) + (fin < 0 ? "" : " …");
}

/**
 * Marca en `texto` los fragmentos que coinciden con las palabras buscadas, sin distinguir
 * acentos ni mayúsculas: buscar "senal" resalta "Señal". El backend busca además por raíz
 * y con erratas, así que no todo resultado tiene por qué tener algo resaltado.
 */
export function resaltar(texto, consulta) {
  const palabras = terminos(consulta);
  if (palabras.length === 0 || !texto) return texto;

  // Versión normalizada del texto y, para cada carácter suyo, su posición en el original.
  let normal = "";
  const origen = [];
  for (let i = 0; i < texto.length; i++) {
    const n = sinAcentos(texto[i]);
    normal += n;
    for (let k = 0; k < n.length; k++) origen.push(i);
  }
  origen.push(texto.length);

  const marcado = new Array(texto.length).fill(false);
  for (const p of palabras) {
    for (let i = normal.indexOf(p); i !== -1; i = normal.indexOf(p, i + 1)) {
      for (let k = origen[i]; k < origen[i + p.length]; k++) marcado[k] = true;
    }
  }

  const trozos = [];
  let inicio = 0;
  for (let i = 1; i <= texto.length; i++) {
    if (i === texto.length || marcado[i] !== marcado[inicio]) {
      const t = texto.slice(inicio, i);
      trozos.push(marcado[inicio] ? <mark key={inicio}>{t}</mark> : t);
      inicio = i;
    }
  }
  return trozos;
}
