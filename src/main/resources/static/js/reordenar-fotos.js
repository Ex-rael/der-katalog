/* =========================================================
   Reordenação de fotos por arrastar.

   Melhoria progressiva: sem este arquivo, as setas ← e →
   continuam reordenando por formulário comum, que é o
   caminho que funciona com teclado e com leitor de tela.
   Este script acrescenta o arrastar para quem tem mouse.

   Em arquivo próprio, e não embutido no HTML, porque a CSP
   é restrita a 'self' e proíbe script inline.
   ========================================================= */
(function () {
  "use strict";

  const galeria = document.getElementById("galeria-de-fotos");
  const formulario = document.getElementById("formulario-de-ordem");

  if (!galeria || !formulario) {
    return;
  }

  galeria.classList.add("arrastavel");

  let arrastada = null;

  function figuras() {
    return Array.prototype.slice.call(galeria.querySelectorAll("figure[data-foto-id]"));
  }

  /* Envia a ordem atual da tela. Reaproveita o formulário que o
     Thymeleaf já renderizou, e com ele o token CSRF — montar a
     requisição à mão exigiria ler o token de algum lugar, e o
     lugar óbvio seria um script inline, que a CSP proíbe. */
  function gravarOrdem() {
    formulario.querySelectorAll("input[name='ids']").forEach(function (campo) {
      campo.remove();
    });

    figuras().forEach(function (figura) {
      const campo = document.createElement("input");
      campo.type = "hidden";
      campo.name = "ids";
      campo.value = figura.dataset.fotoId;
      formulario.appendChild(campo);
    });

    formulario.submit();
  }

  galeria.addEventListener("dragstart", function (evento) {
    const figura = evento.target.closest("figure[data-foto-id]");
    if (!figura) {
      return;
    }
    arrastada = figura;
    figura.classList.add("sendo-arrastada");
    evento.dataTransfer.effectAllowed = "move";
    /* Firefox só inicia o arrasto se houver dado transferido. */
    evento.dataTransfer.setData("text/plain", figura.dataset.fotoId);
  });

  galeria.addEventListener("dragover", function (evento) {
    if (!arrastada) {
      return;
    }
    evento.preventDefault();
    evento.dataTransfer.dropEffect = "move";

    const alvo = evento.target.closest("figure[data-foto-id]");
    if (!alvo || alvo === arrastada) {
      return;
    }

    /* Insere antes ou depois conforme o lado em que o ponteiro
       está, para que a peça acompanhe o movimento do mouse. */
    const area = alvo.getBoundingClientRect();
    const depois = evento.clientX > area.left + area.width / 2;
    galeria.insertBefore(arrastada, depois ? alvo.nextSibling : alvo);
  });

  galeria.addEventListener("dragend", function () {
    if (!arrastada) {
      return;
    }
    arrastada.classList.remove("sendo-arrastada");
    arrastada = null;
    gravarOrdem();
  });
})();
