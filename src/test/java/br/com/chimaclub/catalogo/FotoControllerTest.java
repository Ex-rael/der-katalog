package br.com.chimaclub.catalogo;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.midia.ArmazenamentoFotos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A rota de fotos é a única pública que devolve arquivo do disco. É, por
 * isso, o alvo natural de travessia de diretório vinda da internet.
 */
@AutoConfigureMockMvc
class FotoControllerTest extends BancoDeTesteBase {

    @Autowired MockMvc mvc;
    @Autowired ArmazenamentoFotos armazenamento;

    private MvcResult pegar(String caminho) throws Exception {
        return mvc.perform(get(caminho)).andReturn();
    }

    @Test
    @DisplayName("serve uma foto existente com o tipo fixo e cache longo")
    void serveFotoExistente() throws Exception {
        UUID base = UUID.randomUUID();
        armazenamento.gravar(base, Map.of("mini", new byte[]{1, 2, 3, 4}), "webp");

        MvcResult resposta = pegar("/fotos/" + base + "-mini.webp");

        assertThat(resposta.getResponse().getStatus()).isEqualTo(200);
        assertThat(resposta.getResponse().getContentType()).isEqualTo("image/webp");
        assertThat(resposta.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(resposta.getResponse().getHeader("Content-Disposition")).isEqualTo("inline");
        assertThat(resposta.getResponse().getHeader("Cache-Control"))
                .contains("max-age=31536000").contains("immutable");
    }

    @Test
    @DisplayName("travessia de diretório não devolve arquivo nenhum")
    void recusaTravessiaDeDiretorio() throws Exception {
        String[] tentativas = {
                "/fotos/..%2F..%2F..%2Fetc%2Fpasswd",
                "/fotos/....//....//etc/passwd",
                "/fotos/%2e%2e%2f%2e%2e%2fetc%2fpasswd",
                "/fotos/..%252f..%252fetc%252fpasswd",
                "/fotos/" + UUID.randomUUID() + "-mini.webp%00.php"
        };

        for (String tentativa : tentativas) {
            MvcResult resposta = pegar(tentativa);

            assertThat(resposta.getResponse().getStatus())
                    .as("travessia recusada: %s", tentativa)
                    .isIn(400, 403, 404);
            assertThat(resposta.getResponse().getContentAsString())
                    .as("nenhum conteúdo de /etc pode voltar")
                    .doesNotContain("root:")
                    .doesNotContain("/bin/bash");
        }
    }

    @Test
    @DisplayName("nome fora do padrão responde igual a arquivo inexistente")
    void nomeInvalidoEArquivoAusenteRespondemIgual() throws Exception {
        int nomeInvalido = pegar("/fotos/qualquer-coisa.webp").getResponse().getStatus();
        int arquivoAusente = pegar("/fotos/" + UUID.randomUUID() + "-mini.webp").getResponse().getStatus();

        assertThat(nomeInvalido)
                .as("distinguir os dois diria ao sondador quando um nome é válido mas ausente")
                .isEqualTo(arquivoAusente)
                .isEqualTo(404);
    }

    @Test
    @DisplayName("extensão executável é recusada mesmo com UUID válido no nome")
    void recusaExtensaoExecutavel() throws Exception {
        UUID base = UUID.randomUUID();

        for (String extensao : new String[]{"php", "jsp", "html", "svg", "sh"}) {
            assertThat(pegar("/fotos/" + base + "-mini." + extensao).getResponse().getStatus())
                    .as("extensão %s", extensao)
                    .isEqualTo(404);
        }
    }

    @Test
    @DisplayName("a resposta de erro não traz rastro de pilha")
    void erroNaoTrazRastroDePilha() throws Exception {
        String corpo = pegar("/fotos/invalido.webp").getResponse().getContentAsString();

        assertThat(corpo)
                .doesNotContain("Exception")
                .doesNotContain("br.com.chimaclub")
                .doesNotContainIgnoringCase("springframework");
    }
}
