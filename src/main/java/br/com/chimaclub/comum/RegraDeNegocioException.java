package br.com.chimaclub.comum;

/**
 * Regra de negócio violada. A mensagem é escrita para a dona da loja ler na
 * tela, sem detalhe técnico.
 */
public class RegraDeNegocioException extends RuntimeException {

    public RegraDeNegocioException(String mensagem) {
        super(mensagem);
    }
}
