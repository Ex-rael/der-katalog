package br.com.chimaclub.publico;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
class RecursosEstaticosTest extends BancoDeTesteBase {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("o CSS, as fontes, as imagens e o HTMX são servidos localmente")
    void recursosSaoServidosLocalmente() throws Exception {
        String[] recursos = {
                "/css/chimaclub.css",
                "/css/fontes.css",
                "/css/admin.css",
                "/fontes/marcellus-latin.woff2",
                "/fontes/marcellus-latin-ext.woff2",
                "/fontes/pinyon-script-latin.woff2",
                "/fontes/jost-300-latin.woff2",
                "/fontes/jost-400-latin.woff2",
                "/fontes/jost-500-latin.woff2",
                "/img/logo-chimaclub.png",
                "/img/selo-chimaclub.png",
                "/js/htmx.min.js"
        };
        for (String recurso : recursos) {
            assertThat(mvc.perform(get(recurso)).andReturn().getResponse().getStatus())
                    .as("recurso %s", recurso)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("nenhum CSS pede coisa de domínio externo")
    void nenhumCssPedeNadaDeFora() throws Exception {
        for (String folha : new String[]{"/css/chimaclub.css", "/css/admin.css", "/css/fontes.css"}) {
            String css = mvc.perform(get(folha)).andReturn().getResponse().getContentAsString();

            assertThat(css)
                    .as("um @import externo em %s faria a CSP precisar de exceção permanente", folha)
                    .doesNotContain("https://")
                    .doesNotContain("fonts.googleapis")
                    .doesNotContain("fonts.gstatic");
        }
    }

    @Test
    @DisplayName("as fontes ficam num arquivo só, com separação de subconjunto")
    void fontesFicamNumArquivoSo() throws Exception {
        String fontes = mvc.perform(get("/css/fontes.css"))
                           .andReturn().getResponse().getContentAsString();

        assertThat(fontes).contains("@font-face").contains("/fontes/");
        assertThat(fontes)
                .as("o subconjunto latin cobre os acentos do português; o latin-ext, o resto")
                .contains("U+0000-00FF");
    }

    @Test
    @DisplayName("as duas folhas importam as fontes, e o painel não fica com a fonte do sistema")
    void asDuasFolhasImportamAsFontes() throws Exception {
        // O admin.css usava as variáveis de fonte sem nunca declarar as
        // fontes: o painel inteiro rodava com a serifa do sistema, e o
        // resultado ainda parecia intencional — por isso ninguém notou.
        for (String folha : new String[]{"/css/chimaclub.css", "/css/admin.css"}) {
            String css = mvc.perform(get(folha)).andReturn().getResponse().getContentAsString();

            assertThat(css).as("folha %s", folha).contains("fontes.css");
            assertThat(css)
                    .as("se %s usa as variáveis de fonte, precisa das declarações", folha)
                    .contains("--f-display");
        }
    }
}
