package br.com.chimaclub.admin.web;

import br.com.chimaclub.admin.UsuarioAdmin;
import br.com.chimaclub.admin.UsuarioAdminRepository;
import br.com.chimaclub.comum.ControladorDoPainel;
import br.com.chimaclub.config_loja.ConfiguracaoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

@ControladorDoPainel
public class ConfiguracaoController {

    private final ConfiguracaoService configuracao;
    private final UsuarioAdminRepository usuarios;

    public ConfiguracaoController(ConfiguracaoService configuracao, UsuarioAdminRepository usuarios) {
        this.configuracao = configuracao;
        this.usuarios = usuarios;
    }

    @GetMapping("/admin/configuracao")
    public String abrir(Model modelo) {
        modelo.addAttribute("valores", configuracao.todas());
        return "admin/configuracao";
    }

    @PostMapping("/admin/configuracao")
    public String gravar(@RequestParam Map<String, String> campos, Principal principal,
                         HttpServletRequest requisicao, RedirectAttributes redirecionamento) {

        // Só as chaves conhecidas: um campo extra no formulário não pode
        // criar configuração nova nem sobrescrever o que não está na tela.
        Map<String, String> permitidos = new LinkedHashMap<>();
        for (String chave : new String[]{
                ConfiguracaoService.WHATSAPP_NUMERO,
                ConfiguracaoService.WHATSAPP_EXIBIDO,
                ConfiguracaoService.WHATSAPP_MENSAGEM,
                ConfiguracaoService.INSTAGRAM,
                ConfiguracaoService.LOJA_NOME,
                ConfiguracaoService.LOJA_LEMA}) {
            if (campos.containsKey(chave)) {
                permitidos.put(chave, campos.get(chave).trim());
            }
        }

        configuracao.gravar(permitidos, autor(principal), requisicao);
        redirecionamento.addFlashAttribute("aviso", "Configuração gravada.");
        return "redirect:/admin/configuracao";
    }

    private UsuarioAdmin autor(Principal principal) {
        if (principal == null) {
            return null;
        }
        return usuarios.findByEmailIgnoreCase(principal.getName()).orElse(null);
    }
}
