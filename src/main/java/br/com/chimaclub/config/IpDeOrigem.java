package br.com.chimaclub.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * De onde a requisição veio, para fins de limite por IP.
 *
 * Atrás do Tailscale Funnel, quem abre a conexão TCP é o processo do
 * tailscaled, em 127.0.0.1, e o endereço do visitante chega em
 * X-Forwarded-For. Isso cria uma escolha que precisa ser feita de propósito:
 *
 * Confiar no cabeçalho sempre seria errado. Ele é texto que o cliente
 * controla, e qualquer visitante poderia mandar um valor diferente a cada
 * requisição para nunca cair no mesmo balde — o limite viraria enfeite.
 *
 * Ignorar o cabeçalho sempre também seria errado. Atrás do Funnel, todo
 * mundo chega de 127.0.0.1, e um único visitante abusivo esgotaria o balde
 * compartilhado, derrubando o catálogo para todos os outros.
 *
 * O caminho certo é confiar no cabeçalho apenas quando a conexão vem de um
 * intermediário conhecido, e usar o valor da DIREITA — o último acrescentado,
 * que é o que o nosso próprio intermediário escreveu. Um valor forjado pelo
 * cliente fica à esquerda desse, e é ignorado.
 */
@Component
public class IpDeOrigem {

    private final Set<String> intermediariosConfiaveis;

    public IpDeOrigem(@Value("${app.intermediarios-confiaveis:127.0.0.1,0:0:0:0:0:0:0:1,::1}")
                      List<String> intermediarios) {
        this.intermediariosConfiaveis = Set.copyOf(intermediarios);
    }

    public String de(HttpServletRequest requisicao) {
        String doSocket = requisicao.getRemoteAddr();

        if (doSocket == null || !intermediariosConfiaveis.contains(doSocket)) {
            // Conexão direta: o endereço do socket é a única fonte que o
            // cliente não controla, e portanto a única que vale.
            return doSocket == null ? "desconhecido" : doSocket;
        }

        String encaminhados = requisicao.getHeader("X-Forwarded-For");
        if (encaminhados == null || encaminhados.isBlank()) {
            return doSocket;
        }

        String[] partes = encaminhados.split(",");
        for (int i = partes.length - 1; i >= 0; i--) {
            String candidato = partes[i].trim();
            if (!candidato.isEmpty() && !intermediariosConfiaveis.contains(candidato)) {
                return candidato;
            }
        }
        return doSocket;
    }
}
