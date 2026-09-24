package br.com.chimaclub.catalogo.web;

import br.com.chimaclub.catalogo.dto.ProdutoDetalhe;
import br.com.chimaclub.catalogo.dto.ProdutoResumo;
import br.com.chimaclub.catalogo.service.CatalogoService;
import br.com.chimaclub.catalogo.service.WhatsappService;
import br.com.chimaclub.config_loja.ConfiguracaoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * O catálogo que o cliente vê. Responde pela porta 8080, a única publicada
 * pelo Funnel, e nada aqui depende de sessão.
 */
@Controller
public class CatalogoController {

    private static final int RELACIONADOS_NA_PAGINA = 3;

    private final CatalogoService catalogo;
    private final ConfiguracaoService configuracao;
    private final WhatsappService whatsapp;

    public CatalogoController(CatalogoService catalogo, ConfiguracaoService configuracao,
                              WhatsappService whatsapp) {
        this.catalogo = catalogo;
        this.configuracao = configuracao;
        this.whatsapp = whatsapp;
    }

    @GetMapping("/")
    public String home(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String categoria,
                       Model modelo) {
        preencherGrade(modelo, q, categoria);
        preencherLoja(modelo);
        return "publico/home";
    }

    /**
     * A mesma grade, servida de dois jeitos.
     *
     * Com HTMX, o navegador pede só este fragmento e troca o bloco da
     * listagem sem recarregar. Sem JavaScript, o formulário faz um GET comum
     * para "/" com ?q=, e a página inteira volta já filtrada. Os dois
     * caminhos usam o mesmo fragmento, então não existe markup duplicado
     * para divergir com o tempo.
     */
    @GetMapping("/busca")
    public String busca(@RequestParam(required = false) String q,
                        @RequestParam(required = false) String categoria,
                        @RequestHeader(value = "HX-Request", required = false) String htmx,
                        Model modelo) {
        preencherGrade(modelo, q, categoria);
        if (htmx == null) {
            preencherLoja(modelo);
            return "publico/home";
        }
        return "publico/fragmentos/grade :: grade";
    }

    @GetMapping("/produto/{slug}")
    public String produto(@PathVariable String slug, Model modelo) {
        ProdutoDetalhe detalhe = catalogo.detalhe(slug)
                // 404 e não uma página de "produto indisponível": um produto
                // despublicado não deve confirmar que existe.
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        modelo.addAttribute("produto", detalhe);
        modelo.addAttribute("relacionados", catalogo.relacionados(detalhe, RELACIONADOS_NA_PAGINA));
        preencherLoja(modelo);
        return "publico/produto";
    }

    /**
     * O clique no botão de compra.
     *
     * É a única rota pública que altera estado, e a única isenta de CSRF —
     * porque o "estado" que ela altera é um contador anônimo, e forjar um
     * clique de outro site não causa dano a ninguém. Em troca, ela não pode
     * fazer mais nada: não lê parâmetro, não aceita destino, não consulta
     * nada que o cliente controle.
     */
    @PostMapping("/produto/{slug}/whatsapp")
    public String cliqueNoWhatsapp(@PathVariable String slug,
                                   @RequestHeader(value = "Referer", required = false) String referer,
                                   HttpServletRequest requisicao) {

        String enderecoDaPagina = requisicao.getRequestURL().toString()
                                            .replace("/whatsapp", "");

        return whatsapp.registrarERedirecionar(slug, referer, enderecoDaPagina)
                .map(destino -> "redirect:" + destino)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
    }

    private void preencherGrade(Model modelo, String termo, String categoria) {
        boolean buscando = termo != null && !termo.isBlank();
        boolean filtrando = categoria != null && !categoria.isBlank();

        // A busca por texto tem precedência sobre o filtro de categoria:
        // quem digitou um termo quer o termo, e devolver o cruzamento dos
        // dois daria resultado vazio sem explicação na maioria das vezes.
        List<ProdutoResumo> encontrados = buscando
                ? catalogo.buscar(termo)
                : catalogo.porCategoria(categoria);

        modelo.addAttribute("q", termo);
        modelo.addAttribute("categoria", categoria);
        modelo.addAttribute("buscando", buscando);
        modelo.addAttribute("filtrando", filtrando);
        modelo.addAttribute("total", encontrados.size());
        modelo.addAttribute("grupos", catalogo.agrupadosPorCategoria(encontrados));

        // Os destaques só aparecem no catálogo inteiro: dentro de uma busca
        // ou de uma categoria, eles competiriam com o que a pessoa pediu.
        modelo.addAttribute("destaques",
                buscando || filtrando ? List.<ProdutoResumo>of() : catalogo.destaques());
        modelo.addAttribute("categorias", catalogo.categoriasComProduto());
    }

    private void preencherLoja(Model modelo) {
        modelo.addAttribute("loja", configuracao.todas());
    }
}
