package br.com.chimaclub.publico;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.catalogo.CliqueWhatsappRepository;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class PaginaDeProdutoTest extends BancoDeTesteBase {

    private static final UUID MADEIRA = UUID.fromString("01920000-0000-7000-8000-000000000001");

    @Autowired MockMvc mvc;
    @Autowired ProdutoService produtoService;
    @Autowired ProdutoRepository produtos;
    @Autowired ProdutoFotoRepository fotos;
    @Autowired CliqueWhatsappRepository cliques;

    private UUID publicar(String nome, String preco, int unidades, int quantasFotos) {
        ProdutoForm form = new ProdutoForm();
        form.setNome(nome);
        form.setPrecoEmReais(preco);
        form.setUnidades(unidades);
        form.setCategoriaId(MADEIRA);
        form.setDescricao("Peça feita à mão, com acabamento em resina.");
        UUID id = produtoService.criar(form, null, null);

        Produto produto = produtos.findById(id).orElseThrow();
        for (int i = 0; i < quantasFotos; i++) {
            ProdutoFoto foto = new ProdutoFoto(produto,
                    UUID.randomUUID() + "-media.webp", UUID.randomUUID() + "-mini.webp",
                    1200, 1500, 50_000, "image/webp");
            foto.setPrincipal(i == 0);
            foto.setOrdem(i);
            fotos.save(foto);
        }
        produtoService.publicar(id, true, null, null);
        return id;
    }

    private String corpoDe(String caminho) throws Exception {
        return mvc.perform(get(caminho)).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("a página abre pelo slug, com preço e descrição")
    void abrePeloSlug() throws Exception {
        publicar("Cuia Gold em madeira", "89,90", 3, 1);

        var resposta = mvc.perform(get("/produto/cuia-gold-em-madeira")).andReturn().getResponse();

        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(resposta.getContentAsString())
                .contains("Cuia Gold em madeira")
                .contains("89,90")
                .contains("Peça feita à mão")
                .contains("3 unidades disponíveis");
    }

    @Test
    @DisplayName("slug de rascunho, de excluído e inexistente respondem 404")
    void naoAbreOQueNaoEstaPublicado() throws Exception {
        ProdutoForm rascunho = new ProdutoForm();
        rascunho.setNome("Cuia rascunho");
        rascunho.setPrecoEmReais("89,90");
        rascunho.setUnidades(1);
        produtoService.criar(rascunho, null, null);

        UUID excluida = publicar("Cuia removida", "89,90", 1, 1);
        produtoService.excluir(excluida, null, null);

        for (String slug : new String[]{"cuia-rascunho", "cuia-removida", "nao-existe"}) {
            var resposta = mvc.perform(get("/produto/" + slug)).andReturn().getResponse();

            assertThat(resposta.getStatus()).as("slug %s", slug).isEqualTo(404);
            assertThat(resposta.getContentAsString())
                    .as("a resposta não pode confirmar que o produto existe")
                    .doesNotContain("Cuia removida")
                    .doesNotContain("Exception");
        }
    }

    @Test
    @DisplayName("descrição com HTML sai escapada")
    void descricaoComHtmlSaiEscapada() throws Exception {
        ProdutoForm form = new ProdutoForm();
        form.setNome("Cuia com descrição perigosa");
        form.setPrecoEmReais("89,90");
        form.setUnidades(1);
        form.setDescricao("<img src=x onerror=alert(1)>");
        UUID id = produtoService.criar(form, null, null);

        Produto produto = produtos.findById(id).orElseThrow();
        ProdutoFoto foto = new ProdutoFoto(produto, UUID.randomUUID() + "-media.webp",
                UUID.randomUUID() + "-mini.webp", 1200, 1500, 1000, "image/webp");
        foto.setPrincipal(true);
        fotos.save(foto);
        produtoService.publicar(id, true, null, null);

        assertThat(corpoDe("/produto/cuia-com-descricao-perigosa"))
                .doesNotContain("<img src=x onerror=alert(1)>")
                .contains("&lt;img");
    }

    @Test
    @DisplayName("as três formas de disponibilidade aparecem na tela")
    void tresFormasDeDisponibilidade() throws Exception {
        publicar("Cuia cheia", "89,90", 5, 1);
        publicar("Cuia única", "89,90", 1, 1);
        publicar("Cuia vazia", "89,90", 0, 1);

        assertThat(corpoDe("/produto/cuia-cheia")).contains("5 unidades disponíveis");
        assertThat(corpoDe("/produto/cuia-unica")).contains("Última unidade");
        assertThat(corpoDe("/produto/cuia-vazia"))
                .contains("Esgotado")
                .as("§6.4: esgotado mantém o botão, com mensagem de encomenda")
                .contains("Comprar pelo WhatsApp")
                .contains("encomendar");
    }

    @Test
    @DisplayName("o carrossel aparece com mais de uma foto, e não com uma só")
    void carrosselApareceComMaisDeUmaFoto() throws Exception {
        publicar("Cuia de uma foto", "89,90", 1, 1);
        publicar("Cuia de tres fotos", "89,90", 1, 3);

        assertThat(corpoDe("/produto/cuia-de-uma-foto")).doesNotContain("class=\"carrossel\"");
        assertThat(corpoDe("/produto/cuia-de-tres-fotos"))
                .contains("class=\"carrossel\"")
                .contains("Ver foto 1 de 3")
                .contains("Ver foto 3 de 3");
    }

    @Test
    @DisplayName("toda imagem da página tem texto alternativo")
    void todaImagemTemTextoAlternativo() throws Exception {
        publicar("Cuia ilustrada", "89,90", 2, 3);

        var imagens = java.util.regex.Pattern.compile("<img\\b[^>]*>")
                .matcher(corpoDe("/produto/cuia-ilustrada"));

        int quantas = 0;
        while (imagens.find()) {
            assertThat(imagens.group()).as("requisito não negociável da §7").contains("alt=");
            quantas++;
        }
        assertThat(quantas).isPositive();
    }

    @Test
    @DisplayName("os relacionados da mesma categoria aparecem, sem repetir o aberto")
    void relacionadosAparecem() throws Exception {
        publicar("Cuia principal", "89,90", 1, 1);
        publicar("Cuia vizinha", "99,90", 1, 1);

        String corpo = corpoDe("/produto/cuia-principal");

        assertThat(corpo).contains("Cuia vizinha").contains("/produto/cuia-vizinha");
        assertThat(corpo)
                .as("o produto aberto não pode virar link para si mesmo nos relacionados")
                .doesNotContain("href=\"/produto/cuia-principal\"");
    }

    @Test
    @DisplayName("o clique redireciona para o WhatsApp com a mensagem da configuração")
    void cliqueRedirecionaComAMensagem() throws Exception {
        publicar("Cuia Gold em madeira", "89,90", 2, 1);

        var resposta = mvc.perform(post("/produto/cuia-gold-em-madeira/whatsapp"))
                          .andReturn().getResponse();

        assertThat(resposta.getStatus()).isEqualTo(302);
        String destino = resposta.getRedirectedUrl();

        assertThat(destino).startsWith("https://api.whatsapp.com/send?phone=5551989250481&text=");
        assertThat(URLDecoder.decode(destino, StandardCharsets.UTF_8))
                .contains("Cuia Gold em madeira")
                .contains("/produto/cuia-gold-em-madeira");
    }

    @Test
    @DisplayName("a CSP da página de produto permite o envio chegar ao WhatsApp")
    void cspDaPaginaPermiteOEnvioAoWhatsapp() throws Exception {
        publicar("Cuia Gold em madeira", "89,90", 2, 1);

        // Os dois lados do mesmo fluxo, no mesmo teste de propósito. A CSP
        // cobra form-action do destino do redirecionamento, não só do
        // endereço do envio: com 'self' sozinho o 302 abaixo continuava
        // saindo igualzinho do servidor, e o navegador o descartava. Um
        // teste que olhasse só o 302 diria que está tudo bem.
        String csp = mvc.perform(get("/produto/cuia-gold-em-madeira"))
                        .andReturn().getResponse().getHeader("Content-Security-Policy");

        assertThat(csp).contains("form-action 'self' https://api.whatsapp.com;");

        var resposta = mvc.perform(post("/produto/cuia-gold-em-madeira/whatsapp"))
                          .andReturn().getResponse();

        assertThat(resposta.getStatus()).isEqualTo(302);
        assertThat(resposta.getHeader("Location")).startsWith("https://api.whatsapp.com/send?phone=");
    }

    @Test
    @DisplayName("a origem para onde o clique redireciona está declarada na CSP")
    void origemDoRedirecionamentoEstaNaCsp() throws Exception {
        publicar("Cuia Gold em madeira", "89,90", 2, 1);

        // A ligação entre as duas metades do fluxo, que antes não existia:
        // o destino morava no WhatsappService, a política no CabecalhosConfig,
        // e nada obrigava um a acompanhar o outro. Mudar o destino sem mexer
        // na CSP dava build verde e botão quebrado no navegador — foi assim
        // que o bloqueio chegou em produção.
        String destino = mvc.perform(post("/produto/cuia-gold-em-madeira/whatsapp"))
                            .andReturn().getResponse().getHeader("Location");
        java.net.URI uri = java.net.URI.create(destino);
        String origem = uri.getScheme() + "://" + uri.getHost();

        String csp = mvc.perform(get("/produto/cuia-gold-em-madeira"))
                        .andReturn().getResponse().getHeader("Content-Security-Policy");

        assertThat(origem)
                .as("o destino tem de ser https: http deixaria a conversa "
                    + "à mercê de quem estiver no meio do caminho")
                .startsWith("https://");

        assertThat(csp)
                .as("a CSP precisa declarar %s, senão o navegador barra o "
                    + "redirecionamento que este mesmo teste acabou de ver sair", origem)
                .contains("form-action 'self' " + origem + ";");
    }

    @Test
    @DisplayName("nome com caractere especial é codificado, não colado cru na URL")
    void nomeEhCodificadoNaUrl() throws Exception {
        ProdutoForm form = new ProdutoForm();
        form.setNome("Cuia \"Gold\" & cia #2");
        form.setPrecoEmReais("89,90");
        form.setUnidades(1);
        UUID id = produtoService.criar(form, null, null);
        Produto produto = produtos.findById(id).orElseThrow();
        ProdutoFoto foto = new ProdutoFoto(produto, UUID.randomUUID() + "-media.webp",
                UUID.randomUUID() + "-mini.webp", 1200, 1500, 1000, "image/webp");
        foto.setPrincipal(true);
        fotos.save(foto);
        produtoService.publicar(id, true, null, null);

        String destino = mvc.perform(post("/produto/" + produto.getSlug() + "/whatsapp"))
                            .andReturn().getResponse().getRedirectedUrl();

        assertThat(destino)
                .as("um & cru cortaria a mensagem e viraria outro parâmetro")
                .doesNotContain("& cia")
                .doesNotContain("\"Gold\"");
        assertThat(URLDecoder.decode(destino, StandardCharsets.UTF_8))
                .contains("Cuia \"Gold\" & cia #2");
    }

    @Test
    @DisplayName("o destino é sempre o WhatsApp: não há redirecionamento aberto")
    void naoHaRedirecionamentoAberto() throws Exception {
        publicar("Cuia comum", "89,90", 1, 1);

        // Nenhum parâmetro da requisição pode influenciar o destino.
        String destino = mvc.perform(post("/produto/cuia-comum/whatsapp")
                        .param("destino", "https://site-malicioso.example")
                        .param("redirect", "https://site-malicioso.example")
                        .header("Referer", "https://site-malicioso.example"))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(destino)
                .as("um redirecionamento aberto num domínio de loja é presente para golpista")
                .startsWith("https://api.whatsapp.com/send?phone=")
                .doesNotContain("site-malicioso");
    }

    @Test
    @DisplayName("o clique é registrado sem nada que identifique o visitante")
    void registraSemIdentificarVisitante() throws Exception {
        UUID id = publicar("Cuia contada", "89,90", 1, 1);

        mvc.perform(post("/produto/cuia-contada/whatsapp")
                .header("User-Agent", "Mozilla/5.0 (identificador do visitante)")
                .header("Referer", "http://localhost/produto/cuia-contada"));

        assertThat(cliques.countByProdutoId(id)).isEqualTo(1);

        var clique = cliques.findAll().getFirst();
        assertThat(clique.getCriadoEm()).isNotNull();
        assertThat(String.valueOf(clique.getReferer()))
                .as("o referer diz de qual página do site veio, não quem é o visitante")
                .doesNotContain("Mozilla");
    }

    @Test
    @DisplayName("clique em slug inexistente não registra nada")
    void cliqueEmSlugInexistenteNaoRegistra() throws Exception {
        assertThat(mvc.perform(post("/produto/nao-existe/whatsapp"))
                      .andReturn().getResponse().getStatus())
                .isEqualTo(404);

        assertThat(cliques.count()).isZero();
    }

    @Test
    @DisplayName("clique em produto despublicado não registra nem redireciona")
    void cliqueEmDespublicadoNaoRegistra() throws Exception {
        UUID id = publicar("Cuia recolhida", "89,90", 1, 1);
        produtoService.publicar(id, false, null, null);

        assertThat(mvc.perform(post("/produto/cuia-recolhida/whatsapp"))
                      .andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(cliques.count()).isZero();
    }

    @Test
    @DisplayName("a página do produto não revela nada do que roda por trás")
    void paginaNaoVazaDetalheInterno() throws Exception {
        publicar("Cuia discreta", "89,90", 1, 2);

        assertThat(corpoDe("/produto/cuia-discreta"))
                .doesNotContainIgnoringCase("springframework")
                .doesNotContainIgnoringCase("tomcat")
                .doesNotContain("Exception")
                .doesNotContain("<!--");
    }
}
