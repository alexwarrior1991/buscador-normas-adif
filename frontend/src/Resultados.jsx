import { useState } from "react";
import { codigoParaBuscar, urlFicha, urlNorma } from "./api";
import { extracto, resaltar } from "./resaltar";

const LARGO_EXTRACTO = 240;

/**
 * "Abrir norma": normalmente lo resuelve el backend. Si Java no llega a la web de ADIF desde esta
 * red, se copia el código y se abre el buscador oficial en el navegador para pegarlo allí.
 */
function AbrirNorma({ codigo, accesoAdif }) {
  const [aviso, setAviso] = useState("");

  if (accesoAdif?.disponible !== false) {
    return (
      <a href={urlNorma(codigo)} target="_blank" rel="noreferrer" title="PDF de la norma en la web de ADIF">
        Abrir norma
      </a>
    );
  }

  const abrir = () => {
    const base = codigoParaBuscar(codigo);
    navigator.clipboard?.writeText(base);
    window.open(accesoAdif.buscador, "_blank", "noopener");
    setAviso(`«${base}» copiado: pégalo en «Código»`);
    setTimeout(() => setAviso(""), 6000);
  };

  return (
    <>
      <button className="enlace fuerte" onClick={abrir} title="Copia el código y abre el buscador de ADIF">
        Abrir norma
      </button>
      {aviso && <span className="copiado">{aviso}</span>}
    </>
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
