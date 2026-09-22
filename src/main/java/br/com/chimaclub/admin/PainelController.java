package br.com.chimaclub.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Esqueleto do painel. O conteúdo de verdade — lista de produtos,
 * formulário, upload — entra na Fase 2. Por ora existe para que o teste de
 * isolamento tenha uma rota administrativa real para tentar alcançar.
 */
@RestController
public class PainelController {

    @GetMapping("/admin/painel")
    public String painel() {
        return "painel";
    }
}
