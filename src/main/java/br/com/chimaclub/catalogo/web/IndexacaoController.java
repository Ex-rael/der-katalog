package br.com.chimaclub.catalogo.web;

import br.com.chimaclub.catalogo.dto.ProdutoResumo;
import br.com.chimaclub.catalogo.service.CatalogoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

/**
 * O que os buscadores leem.
 *
 * O sitemap é gerado, e não estático, porque um endereço de produto que
 * deixou de existir e continua listado gera visita a 404 — barata para o
 * buscador, cara para uma hospedagem doméstica.
 */
@Controller
public class IndexacaoController {

    private final CatalogoService catalogo;

    public IndexacaoController(CatalogoService catalogo) {
        this.catalogo = catalogo;
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap(HttpServletRequest requisicao) {
        String base = enderecoBase(requisicao);
        List<ProdutoResumo> publicados = catalogo.publicados();

        StringBuilder xml = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        xml.append("  <url><loc>").append(HtmlUtils.htmlEscape(base))
           .append("/</loc><changefreq>weekly</changefreq><priority>1.0</priority></url>\n");

        for (ProdutoResumo produto : publicados) {
            // O slug já sai do Slugify restrito a [a-z0-9-], mas escapar aqui
            // mantém a garantia local: quem lê este método não precisa ir
            // conferir o Slugify para saber que o XML sai bem formado.
            xml.append("  <url><loc>")
               .append(HtmlUtils.htmlEscape(base + "/produto/" + produto.slug()))
               .append("</loc><changefreq>weekly</changefreq></url>\n");
        }

        xml.append("</urlset>\n");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml.toString());
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots(HttpServletRequest requisicao) {
        // Crawl-delay porque a banda de subida é o ponto fraco desta
        // hospedagem (§4.2): um robô sem limite consome a mesma banda que os
        // clientes precisam para ver as fotos.
        //
        // Disallow em /admin ainda que ele nem responda por esta porta. Não
        // é proteção — robots.txt não protege nada — é evitar que o endereço
        // apareça em listagem de buscador.
        String texto = """
                User-agent: *
                Allow: /
                Disallow: /admin
                Disallow: /busca
                Crawl-delay: 10

                Sitemap: %s/sitemap.xml
                """.formatted(enderecoBase(requisicao));

        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(texto);
    }

    /**
     * Monta a base a partir do que o contêiner sabe da requisição. Atrás do
     * Funnel isso é o nome público; em desenvolvimento, o localhost.
     */
    private static String enderecoBase(HttpServletRequest requisicao) {
        String url = requisicao.getRequestURL().toString();
        int barraDoCaminho = url.indexOf('/', url.indexOf("://") + 3);
        return barraDoCaminho < 0 ? url : url.substring(0, barraDoCaminho);
    }
}
