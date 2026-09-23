package br.com.chimaclub.comum;

import br.com.chimaclub.midia.UploadRecusadoException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * Converte falha em mensagem para a dona da loja, e nada além disso.
 *
 * Atua apenas nos controladores do painel, marcados com
 * @ControladorDoPainel. Na rota pública, uma falha precisa responder com o
 * status real: redirecionar um 404 para a home esconderia o desfecho de
 * quem consome a página, e registraria um erro a cada sondagem.
 *
 * O §A05 exige página de erro genérica. Aqui isso significa: a mensagem que
 * chega à tela é escrita por nós, e o que a exceção trazia — classe, causa,
 * consulta, caminho de arquivo, versão de biblioteca — fica só no log,
 * amarrado a um identificador de correlação que a pessoa pode informar se
 * precisar de ajuda.
 */
@ControllerAdvice(annotations = ControladorDoPainel.class)
public class ManipuladorDeErros {

    private static final Logger LOG = LoggerFactory.getLogger(ManipuladorDeErros.class);

    /** Regra de negócio: a mensagem já foi escrita para ser lida. */
    @ExceptionHandler(RegraDeNegocioException.class)
    public String regraDeNegocio(RegraDeNegocioException recusa, HttpServletRequest requisicao,
                                 RedirectAttributes redirecionamento) {
        redirecionamento.addFlashAttribute("erro", recusa.getMessage());
        return "redirect:" + destinoSeguro(requisicao);
    }

    @ExceptionHandler(UploadRecusadoException.class)
    public String uploadRecusado(UploadRecusadoException recusa, HttpServletRequest requisicao,
                                 RedirectAttributes redirecionamento) {
        redirecionamento.addFlashAttribute("erro", recusa.getMessage());
        return "redirect:" + destinoSeguro(requisicao);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String arquivoGrandeDemais(HttpServletRequest requisicao,
                                      RedirectAttributes redirecionamento) {
        redirecionamento.addFlashAttribute("erro",
                "O envio passou do limite de tamanho. Envie menos fotos por vez, ou imagens menores.");
        return "redirect:" + destinoSeguro(requisicao);
    }

    /**
     * Status deliberado passa direto, com o código que a aplicação escolheu
     * e sem corpo.
     *
     * Cobre tanto o 404 que a rota de fotos lança quanto o 404 que o próprio
     * Spring lança quando nenhum controlador casa com o caminho — ambos são
     * ErrorResponseException. Sem isto o manipulador genérico abaixo os
     * engoliria, transformaria em redirecionamento, e registraria "erro
     * inesperado" a cada sondagem de endereço inexistente: ruído na revisão
     * mensal, e um jeito barato de encher o disco de log a partir da
     * internet.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<Void> statusDeliberado(ErrorResponseException falha) {
        return ResponseEntity.status(falha.getStatusCode()).build();
    }

    /**
     * Qualquer outra falha. O identificador de correlação vai para a tela e
     * para o log; o rastro de pilha, só para o log.
     */
    @ExceptionHandler(Exception.class)
    public String erroInesperado(Exception falha, HttpServletRequest requisicao,
                                 RedirectAttributes redirecionamento) {
        String correlacao = UUID.randomUUID().toString().substring(0, 8);
        LOG.error("erro inesperado [{}] em {} {}",
                correlacao, requisicao.getMethod(), requisicao.getRequestURI(), falha);

        redirecionamento.addFlashAttribute("erro",
                "Algo deu errado. Se o problema continuar, informe o código " + correlacao + ".");
        return "redirect:" + destinoSeguro(requisicao);
    }

    /**
     * Volta para o painel, e não para o endereço de origem. Redirecionar
     * para um valor vindo da requisição abriria redirecionamento aberto, e o
     * ganho seria só de conveniência.
     */
    private static String destinoSeguro(HttpServletRequest requisicao) {
        String caminho = requisicao.getRequestURI();
        return caminho != null && caminho.startsWith("/admin") ? "/admin/produtos" : "/";
    }
}
