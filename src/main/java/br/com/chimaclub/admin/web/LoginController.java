package br.com.chimaclub.admin.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    /**
     * O parâmetro "erro" apenas liga a mensagem na tela. A mensagem é uma
     * só, escrita no template, e não vem daqui: assim não há como um motivo
     * de falha específico vazar para a resposta.
     */
    @GetMapping("/admin/login")
    public String login() {
        return "admin/login";
    }
}
