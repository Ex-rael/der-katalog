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
    @DisplayName("o CSS não pede nada a domínio externo")
    void cssNaoPedeNadaDeFora() throws Exception {
        String css = mvc.perform(get("/css/chimaclub.css"))
                        .andReturn().getResponse().getContentAsString();

        assertThat(css)
                .as("um @import externo faria a CSP precisar de exceção permanente ao Google")
                .doesNotContain("https://")
                .doesNotContain("fonts.googleapis")
                .doesNotContain("fonts.gstatic");
    }

    @Test
    @DisplayName("o CSS declara as fontes locais com separação de subconjunto")
    void cssDeclaraFontesLocais() throws Exception {
        String css = mvc.perform(get("/css/chimaclub.css"))
                        .andReturn().getResponse().getContentAsString();

        assertThat(css).contains("@font-face").contains("/fontes/");
        assertThat(css)
                .as("o subconjunto latin cobre os acentos do português; o latin-ext, o resto")
                .contains("U+0000-00FF");
    }
}
