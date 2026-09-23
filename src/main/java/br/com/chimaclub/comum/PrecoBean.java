package br.com.chimaclub.comum;

import org.springframework.stereotype.Component;

/**
 * Expõe a formatação de preço aos templates, como @preco.formatar(...).
 * Existe só para isso: a lógica continua em Preco, que é estática e
 * testável sem contexto.
 */
@Component("preco")
public class PrecoBean {

    public String formatar(long centavos) {
        return Preco.formatar(centavos);
    }

    public String paraCampo(long centavos) {
        return Preco.paraCampo(centavos);
    }
}
