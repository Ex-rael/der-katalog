package br.com.chimaclub.midia;

/**
 * Upload recusado. A mensagem é escrita para a dona da loja ler na tela, e
 * por isso não carrega detalhe técnico: nem o motivo exato da recusa, nem
 * nada sobre o arquivo enviado, que seria eco de entrada não confiável.
 */
public class UploadRecusadoException extends RuntimeException {

    public UploadRecusadoException(String mensagem) {
        super(mensagem);
    }
}
