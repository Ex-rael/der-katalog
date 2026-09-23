package br.com.chimaclub.midia;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.catalogo.Produto;
import br.com.chimaclub.catalogo.ProdutoFoto;
import br.com.chimaclub.catalogo.ProdutoFotoRepository;
import br.com.chimaclub.catalogo.ProdutoRepository;
import br.com.chimaclub.catalogo.dto.ProdutoForm;
import br.com.chimaclub.catalogo.service.ProdutoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LimpezaDeFotosTest extends BancoDeTesteBase {

    @Autowired LimpezaDeFotos limpeza;
    @Autowired ProdutoService produtoService;
    @Autowired ProdutoRepository produtos;
    @Autowired ProdutoFotoRepository fotos;
    @Autowired ArmazenamentoFotos armazenamento;

    /** Cria um produto com uma foto cujos três arquivos existem no disco. */
    private UUID produtoComFotoNoDisco(String nome) {
        ProdutoForm form = new ProdutoForm();
        form.setNome(nome);
        form.setPrecoEmReais("89,90");
        form.setUnidades(1);
        UUID id = produtoService.criar(form, null, null);

        UUID base = UUID.randomUUID();
        armazenamento.gravar(base, Map.of(
                "mini", new byte[]{1}, "media", new byte[]{2}, "grande", new byte[]{3}), "webp");

        Produto produto = produtos.findById(id).orElseThrow();
        ProdutoFoto foto = new ProdutoFoto(produto,
                base + "-media.webp", base + "-mini.webp", 1200, 1500, 1000, "image/webp");
        foto.setPrincipal(true);
        fotos.save(foto);
        return id;
    }

    private void marcarExcluidoEm(UUID produtoId, Instant quando) {
        Produto produto = produtos.findById(produtoId).orElseThrow();
        produto.setExcluidoEm(quando);
        produto.setPublicado(false);
        produtos.saveAndFlush(produto);
    }

    private String arquivoMediaDe(UUID produtoId) {
        return fotos.findByProdutoIdOrderByOrdemAsc(produtoId).getFirst().getArquivo();
    }

    @Test
    @DisplayName("apaga a foto de produto excluído há mais de trinta dias")
    void apagaFotoDeProdutoExcluidoHaMuito() {
        UUID id = produtoComFotoNoDisco("Cuia antiga");
        String arquivo = arquivoMediaDe(id);
        marcarExcluidoEm(id, Instant.now().minus(LimpezaDeFotos.PRAZO).minusSeconds(3600));

        int apagados = limpeza.executar();

        assertThat(apagados).as("mini, media e grande").isEqualTo(3);
        assertThat(armazenamento.existe(arquivo)).isFalse();
        assertThat(fotos.countByProdutoId(id)).isZero();
    }

    @Test
    @DisplayName("não toca na foto de produto excluído ontem")
    void naoApagaExclusaoRecente() {
        UUID id = produtoComFotoNoDisco("Cuia de ontem");
        String arquivo = arquivoMediaDe(id);
        marcarExcluidoEm(id, Instant.now().minusSeconds(24 * 3600));

        assertThat(limpeza.executar()).isZero();
        assertThat(armazenamento.existe(arquivo))
                .as("os trinta dias existem para dar tempo de desfazer um engano")
                .isTrue();
        assertThat(fotos.countByProdutoId(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("no limite exato do prazo, ainda não apaga")
    void noLimiteDoPrazoAindaNaoApaga() {
        UUID id = produtoComFotoNoDisco("Cuia no limite");
        marcarExcluidoEm(id, Instant.now().minus(LimpezaDeFotos.PRAZO).plusSeconds(60));

        assertThat(limpeza.executar()).isZero();
    }

    @Test
    @DisplayName("nunca apaga foto de produto ativo, nem por engano")
    void nuncaApagaFotoDeProdutoAtivo() {
        UUID ativo = produtoComFotoNoDisco("Cuia no catálogo");
        UUID antigo = produtoComFotoNoDisco("Cuia sumida");
        String arquivoDoAtivo = arquivoMediaDe(ativo);
        marcarExcluidoEm(antigo, Instant.now().minus(LimpezaDeFotos.PRAZO).minusSeconds(3600));

        limpeza.executar();

        assertThat(armazenamento.existe(arquivoDoAtivo))
                .as("um produto no ar não pode perder a foto por causa de outro")
                .isTrue();
        assertThat(fotos.countByProdutoId(ativo)).isEqualTo(1);
    }

    @Test
    @DisplayName("um arquivo já ausente no disco não derruba a rotina")
    void arquivoAusenteNaoDerrubaARotina() {
        UUID id = produtoComFotoNoDisco("Cuia sem arquivo");
        String arquivo = arquivoMediaDe(id);
        String base = arquivo.replace("-media.webp", "");

        // Alguém apagou à mão, ou uma execução anterior parou no meio.
        armazenamento.apagar(base + "-mini.webp");
        armazenamento.apagar(base + "-media.webp");
        marcarExcluidoEm(id, Instant.now().minus(LimpezaDeFotos.PRAZO).minusSeconds(3600));

        assertThatCode(() -> limpeza.executar())
                .as("derrubar a limpeza por um arquivo faltando faria todo o resto acumular")
                .doesNotThrowAnyException();
        assertThat(fotos.countByProdutoId(id)).isZero();
    }

    @Test
    @DisplayName("a rotina não faz nada quando não há o que limpar")
    void semNadaParaLimparNaoFazNada() {
        produtoComFotoNoDisco("Cuia tranquila");

        assertThat(limpeza.executar()).isZero();
    }

    @Test
    @DisplayName("apaga as três versões, e não só a que está gravada no banco")
    void apagaAsTresVersoes() {
        UUID id = produtoComFotoNoDisco("Cuia de três versões");
        String base = arquivoMediaDe(id).replace("-media.webp", "");
        marcarExcluidoEm(id, Instant.now().minus(LimpezaDeFotos.PRAZO).minusSeconds(3600));

        limpeza.executar();

        for (String versao : new String[]{"mini", "media", "grande"}) {
            assertThat(armazenamento.existe(base + "-" + versao + ".webp"))
                    .as("versão %s: a grande é a mais pesada, e é a que não está no banco", versao)
                    .isFalse();
        }
    }
}
