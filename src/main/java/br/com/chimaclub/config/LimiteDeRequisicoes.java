package br.com.chimaclub.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite de requisições por IP (§A04).
 *
 * Numa hospedagem doméstica o ponto fraco é a banda de subida, e não o
 * processador: um robô sem limite consome a mesma banda que os clientes
 * precisam para ver as fotos. O limite existe para que um visitante sozinho
 * não tire o catálogo do ar para os outros.
 *
 * Fica claro o que ele NÃO faz: um ataque volumétrico, vindo de muitos
 * endereços, não é absorvível nesta infraestrutura, e a §4.2 do plano aceita
 * a indisponibilidade temporária como resposta. Este controle trata do
 * abuso de origem única, que é o caso comum.
 */
@Component
public class LimiteDeRequisicoes {

    /** Faixas de limite, da mais apertada para a mais larga. */
    public enum Faixa {
        /**
         * O login é a porta que o bloqueio por tentativa já defende. Este
         * limite é a camada anterior: impede que alguém consuma a capacidade
         * do servidor tentando, mesmo que a conta já esteja bloqueada.
         */
        LOGIN(5, Duration.ofMinutes(1)),

        /** Upload é caro: decodifica, redimensiona e grava três arquivos. */
        UPLOAD(10, Duration.ofMinutes(1)),

        /** Páginas: cada uma custa consulta ao banco e renderização. */
        PAGINA(60, Duration.ofMinutes(1)),

        /**
         * Estático e fotos, numa faixa larga de propósito. Uma única visita
         * à home pede o HTML, o CSS, cinco fontes, o HTMX e uma miniatura
         * por produto — perto de trinta requisições. Se caíssem na faixa das
         * páginas, a segunda visita de um cliente legítimo já seria barrada,
         * e a primeira impressão do catálogo seria um erro.
         */
        RECURSO(300, Duration.ofMinutes(1));

        private final int quantidade;
        private final Duration janela;

        Faixa(int quantidade, Duration janela) {
            this.quantidade = quantidade;
            this.janela = janela;
        }

        public int quantidade() {
            return quantidade;
        }

        public long segundosDaJanela() {
            return janela.toSeconds();
        }

        Bandwidth largura() {
            return Bandwidth.builder()
                    .capacity(quantidade)
                    .refillIntervally(quantidade, janela)
                    .build();
        }
    }

    /**
     * Em memória, e não distribuído: há um processo só, e a §1.5 prevê
     * centenas de visitas por dia. Um mapa por IP e faixa cabe folgado, e
     * perder o estado num reinício custa, no pior caso, uma janela.
     */
    private final Map<String, Bucket> baldes = new ConcurrentHashMap<>();

    /** Devolve true se a requisição cabe no limite; false se estourou. */
    public boolean permitir(String ip, Faixa faixa) {
        return baldes.computeIfAbsent(faixa.name() + "|" + ip,
                        chave -> Bucket.builder().addLimit(faixa.largura()).build())
                .tryConsume(1);
    }

    /** Quantos segundos até a próxima permissão, para o cabeçalho Retry-After. */
    public long segundosParaTentarDeNovo(Faixa faixa) {
        return faixa.segundosDaJanela();
    }

    /** Usado pelos testes para começar cada caso do zero. */
    public void limpar() {
        baldes.clear();
    }

    /**
     * Qual faixa se aplica a esta requisição.
     *
     * A ordem importa: o login precisa ser reconhecido antes das rotas do
     * painel em geral, e os recursos estáticos antes das páginas.
     */
    public Faixa faixaDe(HttpServletRequest requisicao) {
        String caminho = requisicao.getRequestURI();
        String metodo = requisicao.getMethod();

        if (caminho.startsWith("/admin/login") && "POST".equals(metodo)) {
            return Faixa.LOGIN;
        }
        if (caminho.contains("/fotos") && "POST".equals(metodo)) {
            return Faixa.UPLOAD;
        }
        if (caminho.startsWith("/fotos/") || caminho.startsWith("/css/")
                || caminho.startsWith("/js/") || caminho.startsWith("/fontes/")
                || caminho.startsWith("/img/")) {
            return Faixa.RECURSO;
        }
        return Faixa.PAGINA;
    }
}
