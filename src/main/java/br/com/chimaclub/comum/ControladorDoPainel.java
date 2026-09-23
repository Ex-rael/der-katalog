package br.com.chimaclub.comum;

import org.springframework.stereotype.Controller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um controlador do painel administrativo.
 *
 * Serve para o ManipuladorDeErros saber onde atuar. O padrão de responder a
 * uma falha com redirecionamento e mensagem na próxima tela só faz sentido
 * para formulário do painel; na rota pública ele transformaria um 404 em
 * redirecionamento para a home, escondendo o status real de quem consome a
 * página — e registrando um erro a cada sondagem de endereço inexistente.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Controller
public @interface ControladorDoPainel {
}
