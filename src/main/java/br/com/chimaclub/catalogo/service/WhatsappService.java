package br.com.chimaclub.catalogo.service;

import br.com.chimaclub.catalogo.CliqueWhatsapp;
import br.com.chimaclub.catalogo.CliqueWhatsappRepository;
import br.com.chimaclub.catalogo.Produto;
import br.com.chimaclub.catalogo.ProdutoRepository;
import br.com.chimaclub.config_loja.ConfiguracaoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Monta o endereço que leva a conversa para o WhatsApp e conta o clique.
 */
@Service
public class WhatsappService {

    /** Único destino possível. Não vem de parâmetro, nem de configuração. */
    private static final String BASE = "https://wa.me/";

    private static final int LIMITE_DO_REFERER = 300;

    private final ProdutoRepository produtos;
    private final CliqueWhatsappRepository cliques;
    private final ConfiguracaoService configuracao;

    public WhatsappService(ProdutoRepository produtos, CliqueWhatsappRepository cliques,
                           ConfiguracaoService configuracao) {
        this.produtos = produtos;
        this.cliques = cliques;
        this.configuracao = configuracao;
    }

    /**
     * Registra o clique e devolve para onde redirecionar.
     *
     * O destino é construído inteiramente a partir de dados do servidor: a
     * base é constante, o número vem da configuração e o nome vem do banco.
     * Nenhum valor da requisição entra aqui, e é isso que impede a rota de
     * virar redirecionamento aberto — que seria grave numa página pública,
     * porque um link do próprio domínio da loja levaria a um site qualquer.
     */
    @Transactional
    public Optional<String> registrarERedirecionar(String slug, String referer, String enderecoDaPagina) {
        Optional<Produto> encontrado = produtos.findBySlugAndExcluidoEmIsNull(slug)
                                               .filter(Produto::isPublicado);
        if (encontrado.isEmpty()) {
            return Optional.empty();
        }
        Produto produto = encontrado.get();

        cliques.save(new CliqueWhatsapp(produto, truncar(referer)));

        return Optional.of(BASE + numeroDaLoja()
                + "?text=" + URLEncoder.encode(mensagem(produto, enderecoDaPagina), StandardCharsets.UTF_8));
    }

    private String numeroDaLoja() {
        // Só dígitos, ainda que a configuração já valide: o número entra numa
        // URL, e uma barra ou interrogação aqui mudaria o destino inteiro.
        return configuracao.valor(ConfiguracaoService.WHATSAPP_NUMERO).replaceAll("\\D", "");
    }

    private String mensagem(Produto produto, String enderecoDaPagina) {
        String modelo = configuracao.valor(ConfiguracaoService.WHATSAPP_MENSAGEM);
        if (modelo.isBlank()) {
            modelo = "Olá! Gostaria de saber a disponibilidade da {produto}.";
        }

        String texto = modelo.replace("{produto}", produto.getNome());
        if (enderecoDaPagina != null && !enderecoDaPagina.isBlank()) {
            texto = texto + "\n" + enderecoDaPagina;
        }
        return texto;
    }

    private static String truncar(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        // Sem quebra de linha, e limitado: é texto que chega do cliente.
        String limpo = referer.replaceAll("[\\r\\n]", "");
        return limpo.length() <= LIMITE_DO_REFERER ? limpo : limpo.substring(0, LIMITE_DO_REFERER);
    }
}
