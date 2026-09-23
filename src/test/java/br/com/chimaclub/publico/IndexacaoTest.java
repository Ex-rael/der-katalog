package br.com.chimaclub.publico;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
class IndexacaoTest extends BancoDeTesteBase {

    @Autowired MockMvc mvc;
    @Autowired ProdutoService produtoService;
    @Autowired ProdutoRepository produtos;
    @Autowired ProdutoFotoRepository fotos;

    private UUID publicar(String nome) {
        ProdutoForm form = new ProdutoForm();
        form.setNome(nome);
        form.setPrecoEmReais("89,90");
        form.setUnidades(1);
        UUID id = produtoService.criar(form, null, null);

        Produto produto = produtos.findById(id).orElseThrow();
        ProdutoFoto foto = new ProdutoFoto(produto, UUID.randomUUID() + "-media.webp",
                UUID.randomUUID() + "-mini.webp", 1200, 1500, 1000, "image/webp");
        foto.setPrincipal(true);
        fotos.save(foto);
        produtoService.publicar(id, true, null, null);
        return id;
    }

    private String corpoDe(String caminho) throws Exception {
        return mvc.perform(get(caminho)).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("o sitemap lista a home e os produtos publicados")
    void sitemapListaOPublicado() throws Exception {
        publicar("Cuia Gold em madeira");

        String xml = corpoDe("/sitemap.xml");

        assertThat(xml)
                .contains("<urlset")
                .contains("/produto/cuia-gold-em-madeira");
    }

    @Test
    @DisplayName("o sitemap não lista rascunho nem produto excluído")
    void sitemapNaoListaOQueNaoEstaPublicado() throws Exception {
        ProdutoForm rascunho = new ProdutoForm();
        rascunho.setNome("Cuia rascunho");
        rascunho.setPrecoEmReais("89,90");
        rascunho.setUnidades(1);
        produtoService.criar(rascunho, null, null);

        UUID excluida = publicar("Cuia removida");
        produtoService.excluir(excluida, null, null);

        String xml = corpoDe("/sitemap.xml");

        assertThat(xml)
                .as("endereço listado que responde 404 gera visita inútil de robô")
                .doesNotContain("cuia-rascunho")
                .doesNotContain("cuia-removida");
    }

    @Test
    @DisplayName("o sitemap é XML bem formado, mesmo com nome de produto esquisito")
    void sitemapEhXmlBemFormado() throws Exception {
        publicar("Cuia \"Gold\" & cia <teste>");
        publicar("Cuia comum");

        String xml = corpoDe("/sitemap.xml");

        assertThatCode(() -> DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))))
                .as("um & cru quebraria o arquivo para todo buscador")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("o robots.txt pede intervalo entre visitas e aponta o sitemap")
    void robotsPedeIntervaloEApontaOSitemap() throws Exception {
        String robots = corpoDe("/robots.txt");

        assertThat(robots)
                .contains("User-agent: *")
                .as("§4.2: a banda de subida é o ponto fraco desta hospedagem")
                .contains("Crawl-delay: 10")
                .contains("Sitemap:")
                .contains("/sitemap.xml");
    }

    @Test
    @DisplayName("o robots.txt mantém o painel fora das listagens de buscador")
    void robotsMantemOPainelForaDasListagens() throws Exception {
        assertThat(corpoDe("/robots.txt"))
                .as("não é proteção — robots.txt não protege nada — é não anunciar o endereço")
                .contains("Disallow: /admin");
    }

    @Test
    @DisplayName("sitemap e robots abrem sem sessão, pela porta pública")
    void abremSemSessao() throws Exception {
        assertThat(mvc.perform(get("/sitemap.xml")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/robots.txt")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
