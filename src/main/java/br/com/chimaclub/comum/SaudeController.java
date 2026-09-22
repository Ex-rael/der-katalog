package br.com.chimaclub.comum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SaudeController {

    /**
     * Verificação simples, sem nenhum detalhe interno: o Actuator, que
     * informa estado de banco e de disco, fica só na porta administrativa.
     */
    @GetMapping("/saude")
    public String saude() {
        return "ok";
    }
}
