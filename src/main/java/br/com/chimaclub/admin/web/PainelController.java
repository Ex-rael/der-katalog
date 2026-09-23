package br.com.chimaclub.admin.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PainelController {

    @GetMapping("/admin/produtos")
    public String produtos() {
        return "admin/produtos";
    }

    @GetMapping("/admin/painel")
    public String painel() {
        return "redirect:/admin/produtos";
    }
}
