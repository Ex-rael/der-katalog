package br.com.chimaclub.catalogo;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.catalogo.dto.ProdutoForm;
import br.com.chimaclub.catalogo.service.FotoService;
import br.com.chimaclub.catalogo.service.ProdutoService;
import br.com.chimaclub.midia.UploadRecusadoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FotoServiceTest extends BancoDeTesteBase {

    @Autowired FotoService fotoService;
    @Autowired ProdutoService produtoService;
    @Autowired ProdutoRepository produtos;
    @Autowired ProdutoFotoRepository fotos;

    private UUID produtoId;

    @BeforeEach
    void criarProduto() {
        ProdutoForm form = new ProdutoForm();
        form.setNome("Cuia de teste");
        form.setPrecoEmReais("89,90");
        form.setUnidades(2);
        produtoId = produtoService.criar(form, null, null);
    }

    private static MockMultipartFile jpeg(String nome) throws Exception {
        BufferedImage imagem = new BufferedImage(1200, 1500, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ImageIO.write(imagem, "jpg", saida);
        return new MockMultipartFile("arquivos", nome, "image/jpeg", saida.toByteArray());
    }

    private UUID enviarFoto(String nome) throws Exception {
        return fotoService.enviar(produtoId, jpeg(nome), null, null);
    }

    @Test
    @DisplayName("§6.5: a primeira foto enviada vira principal automaticamente")
    void primeiraFotoViraPrincipal() throws Exception {
        UUID primeira = enviarFoto("uma.jpg");

        assertThat(fotos.findById(primeira).orElseThrow().isPrincipal()).isTrue();
    }

    @Test
    @DisplayName("a segunda foto não rouba a principal da primeira")
    void segundaFotoNaoViraPrincipal() throws Exception {
        UUID primeira = enviarFoto("uma.jpg");
        UUID segunda = enviarFoto("duas.jpg");

        assertThat(fotos.findById(primeira).orElseThrow().isPrincipal()).isTrue();
        assertThat(fotos.findById(segunda).orElseThrow().isPrincipal()).isFalse();
    }

    @Test
    @DisplayName("§6.5: ao excluir a principal, a próxima na ordem assume")
    void proximaAssumeAoExcluirAPrincipal() throws Exception {
        UUID primeira = enviarFoto("uma.jpg");
        UUID segunda = enviarFoto("duas.jpg");
        UUID terceira = enviarFoto("tres.jpg");

        fotoService.excluir(produtoId, primeira, null, null);

        assertThat(fotos.findById(segunda).orElseThrow().isPrincipal())
                .as("sem isto o produto ficaria sem capa e sumiria da grade da home")
                .isTrue();
        assertThat(fotos.findById(terceira).orElseThrow().isPrincipal()).isFalse();
    }

    @Test
    @DisplayName("nunca existem duas principais ao mesmo tempo")
    void nuncaDuasPrincipais() throws Exception {
        UUID primeira = enviarFoto("uma.jpg");
        UUID segunda = enviarFoto("duas.jpg");

        fotoService.definirPrincipal(produtoId, segunda, null, null);

        List<ProdutoFoto> todas = fotos.findByProdutoIdOrderByOrdemAsc(produtoId);
        assertThat(todas).filteredOn(ProdutoFoto::isPrincipal).hasSize(1);
        assertThat(fotos.findById(segunda).orElseThrow().isPrincipal()).isTrue();
        assertThat(fotos.findById(primeira).orElseThrow().isPrincipal()).isFalse();
    }

    @Test
    @DisplayName("§6.3: excluir a última foto despublica o produto")
    void excluirUltimaFotoDespublica() throws Exception {
        UUID unica = enviarFoto("unica.jpg");
        produtoService.publicar(produtoId, true, null, null);
        assertThat(produtos.findById(produtoId).orElseThrow().isPublicado()).isTrue();

        fotoService.excluir(produtoId, unica, null, null);

        assertThat(produtos.findById(produtoId).orElseThrow().isPublicado())
                .as("um produto publicado sem foto quebraria a grade da home")
                .isFalse();
    }

    @Test
    @DisplayName("um arquivo recusado não deixa rastro no banco nem no disco")
    void arquivoRecusadoNaoDeixaRastro() {
        MockMultipartFile disfarcado = new MockMultipartFile(
                "arquivos", "backdoor.jpg", "image/jpeg", "<?php system($_GET['c']); ?>".getBytes());

        assertThatThrownBy(() -> fotoService.enviar(produtoId, disfarcado, null, null))
                .isInstanceOf(UploadRecusadoException.class);

        assertThat(fotos.countByProdutoId(produtoId))
                .as("validar antes de gravar é o que garante isto")
                .isZero();
    }

    @Test
    @DisplayName("a foto gravada aponta para arquivos com nome gerado pelo sistema")
    void nomesSaoGeradosPeloSistema() throws Exception {
        UUID id = enviarFoto("MINHA FOTO PESSOAL.jpg");

        ProdutoFoto foto = fotos.findById(id).orElseThrow();

        assertThat(foto.getArquivo())
                .as("o nome enviado nunca vai para o disco nem volta ao navegador")
                .doesNotContain("MINHA")
                .doesNotContain("PESSOAL")
                .matches("[0-9a-f-]{36}-media\\.(webp|jpg)");
        assertThat(foto.getArquivoMini()).matches("[0-9a-f-]{36}-mini\\.(webp|jpg)");
    }

    @Test
    @DisplayName("enviar foto para produto inexistente ou excluído é recusado")
    void recusaProdutoInexistenteOuExcluido() throws Exception {
        assertThatThrownBy(() -> fotoService.enviar(UUID.randomUUID(), jpeg("x.jpg"), null, null))
                .isInstanceOf(br.com.chimaclub.comum.RegraDeNegocioException.class);

        produtoService.excluir(produtoId, null, null);
        assertThatThrownBy(() -> fotoService.enviar(produtoId, jpeg("x.jpg"), null, null))
                .isInstanceOf(br.com.chimaclub.comum.RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("não se exclui foto de outro produto passando o identificador dele")
    void naoExcluiFotoDeOutroProduto() throws Exception {
        UUID fotoDoPrimeiro = enviarFoto("uma.jpg");

        ProdutoForm outro = new ProdutoForm();
        outro.setNome("Outro produto");
        outro.setPrecoEmReais("99,90");
        outro.setUnidades(1);
        UUID outroId = produtoService.criar(outro, null, null);

        assertThatThrownBy(() -> fotoService.excluir(outroId, fotoDoPrimeiro, null, null))
                .as("o identificador da foto sozinho não pode autorizar a exclusão")
                .isInstanceOf(br.com.chimaclub.comum.RegraDeNegocioException.class);

        assertThat(fotos.findById(fotoDoPrimeiro)).isPresent();
    }

    @Test
    @DisplayName("a reordenação grava a nova posição de cada foto")
    void reordena() throws Exception {
        UUID primeira = enviarFoto("uma.jpg");
        UUID segunda = enviarFoto("duas.jpg");
        UUID terceira = enviarFoto("tres.jpg");

        fotoService.reordenar(produtoId, List.of(terceira, primeira, segunda), null, null);

        assertThat(fotos.findByProdutoIdOrderByOrdemAsc(produtoId))
                .extracting(ProdutoFoto::getId)
                .containsExactly(terceira, primeira, segunda);
    }

    @Test
    @DisplayName("toda foto enviada e excluída vai para a auditoria")
    void auditaEnvioEExclusao(@Autowired br.com.chimaclub.admin.EventoAuditoriaRepository eventos)
            throws Exception {
        UUID id = enviarFoto("uma.jpg");
        fotoService.excluir(produtoId, id, null, null);

        assertThat(eventos.findAll())
                .extracting(br.com.chimaclub.admin.EventoAuditoria::getAcao)
                .contains("FOTO_ENVIADA", "FOTO_EXCLUIDA");
    }
}
