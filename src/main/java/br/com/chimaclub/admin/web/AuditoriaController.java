package br.com.chimaclub.admin.web;

import br.com.chimaclub.admin.EventoAuditoriaRepository;
import br.com.chimaclub.comum.ControladorDoPainel;
import org.springframework.data.domain.PageRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@ControladorDoPainel
public class AuditoriaController {

    private static final int POR_PAGINA = 50;

    private final EventoAuditoriaRepository eventos;

    public AuditoriaController(EventoAuditoriaRepository eventos) {
        this.eventos = eventos;
    }

    @GetMapping("/admin/auditoria")
    public String listar(@RequestParam(defaultValue = "0") int pagina, Model modelo) {
        // A revisão mensal da §A09 são dez minutos procurando rajada de
        // falha de login e alteração inesperada; a ordem por data decrescente
        // é o que torna isso possível sem ferramenta nenhuma.
        modelo.addAttribute("eventos",
                eventos.findAllByOrderByCriadoEmDesc(PageRequest.of(Math.max(0, pagina), POR_PAGINA)));
        modelo.addAttribute("pagina", Math.max(0, pagina));
        return "admin/auditoria";
    }
}
