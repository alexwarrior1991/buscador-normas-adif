import { useState } from "react";
import { fechaCorta, urlFicha, urlNorma } from "./api";
import { extracto, resaltar } from "./resaltar";

const LARGO_EXTRACTO = 240;

/**
 * "Abrir norma": el backend decide. Si llega a la web de ADIF, la consulta al momento; si no (algunas
 * redes solo dejan pasar navegadores), usa el mapa de normas y tu navegador descarga el PDF de ADIF.
 */
function AbrirNorma({ codigo, accesoAdif }) {
  const titulo =
    accesoAdif?.disponible === false
      ? `Se descarga de la web de ADIF con tu navegador (mapa de normas del ${fechaCorta(accesoAdif.mapa)})`
      : "PDF de la norma en la web de ADIF";
  return (
    <a href={urlNorma(codigo)} target="_blank" rel="noreferrer" title={titulo}>
      Abrir norma
    </a>
  );
}

function Fila({ norma, consulta, accesoAdif }) {
  const [copiado, setCopiado] = useState(false);
  const [abierto, setAbierto] = useState(false);

  const copiar = async () => {
    await navigator.clipboard?.writeText(norma.codigo);
    setCopiado(true);
    setTimeout(() => setCopiado(false), 1200);
  };

  const largo = norma.objeto.length > LARGO_EXTRACTO;

  return (
    <li className="fila">
      <button className="codigo" onClick={copiar} title="Copiar código">
        {copiado ? "¡Copiado!" : resaltar(norma.codigo, consulta)}
      </button>
      <div className="cuerpo">
        <div className="titulo">{resaltar(norma.titulo, consulta)}</div>
        <div className="meta">
          <span>{resaltar(norma.especialidad, consulta)}</span>
          {norma.edicion && <span>{norma.edicion}</span>}
          {norma.fecha && <span>{norma.fecha}</span>}
        </div>
        {norma.deroga && (
          <div className="deroga">
            <strong>Deroga a:</strong> {resaltar(norma.deroga, consulta)}
          </div>
        )}
        {norma.objeto && (
          <div className={abierto ? "objeto abierto" : "objeto"}>
            {resaltar(abierto ? norma.objeto : extracto(norma.objeto, consulta, LARGO_EXTRACTO), consulta)}
            {largo && (
              <button className="enlace" onClick={() => setAbierto(!abierto)}>
                {abierto ? "ver menos" : "ver más"}
              </button>
            )}
          </div>
        )}
      </div>
      <div className="acciones">
        <AbrirNorma codigo={norma.codigo} accesoAdif={accesoAdif} />
        <a className="secundaria" href={urlFicha(norma.pagina)} target="_blank" rel="noreferrer" title={`Página ${norma.pagina} del catálogo`}>
          Ver ficha
        </a>
      </div>
    </li>
  );
}

export function Resultados({ normas, consulta, accesoAdif }) {
  return (
    <ul className="resultados">
      {normas.map((n) => (
        <Fila key={n.pagina} norma={n} consulta={consulta} accesoAdif={accesoAdif} />
      ))}
    </ul>
  );
}
