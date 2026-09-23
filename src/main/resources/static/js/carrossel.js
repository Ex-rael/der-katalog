/* =========================================================
   Carrossel da página de produto.

   Melhoria progressiva: sem este arquivo, as miniaturas já
   formam uma faixa rolável e a foto em destaque continua
   visível. O que ele acrescenta é trocar o destaque ao
   escolher uma miniatura, sem recarregar a página.

   Em arquivo próprio, e não embutido no HTML, porque a CSP
   da Fase 4 fica restrita a 'self' e proíbe script inline.
   ========================================================= */
(function () {
  "use strict";

  const destaque = document.getElementById("foto-em-destaque");
  const miniaturas = document.querySelectorAll(".miniatura-do-carrossel");

  if (!destaque || miniaturas.length === 0) {
    return;
  }

  function mostrar(botao) {
    const endereco = botao.dataset.foto;
    if (!endereco) {
      return;
    }
    destaque.src = endereco;

    const imagem = botao.querySelector("img");
    if (imagem && imagem.alt) {
      destaque.alt = imagem.alt;
    }

    miniaturas.forEach(function (outro) {
      outro.setAttribute("aria-current", String(outro === botao));
    });
  }

  miniaturas.forEach(function (botao) {
    botao.addEventListener("click", function () {
      mostrar(botao);
    });

    /* Setas do teclado percorrem as miniaturas, como se espera
       de um grupo de botões relacionados. */
    botao.addEventListener("keydown", function (evento) {
      const passo = evento.key === "ArrowRight" ? 1 : evento.key === "ArrowLeft" ? -1 : 0;
      if (passo === 0) {
        return;
      }
      evento.preventDefault();

      const lista = Array.prototype.slice.call(miniaturas);
      const atual = lista.indexOf(botao);
      const proximo = lista[(atual + passo + lista.length) % lista.length];

      proximo.focus();
      mostrar(proximo);
    });
  });
})();
