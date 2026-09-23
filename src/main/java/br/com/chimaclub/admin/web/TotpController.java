package br.com.chimaclub.admin.web;

import br.com.chimaclub.admin.Acao;
import br.com.chimaclub.admin.AuditoriaService;
import br.com.chimaclub.admin.UsuarioAdmin;
import br.com.chimaclub.admin.UsuarioAdminRepository;
import br.com.chimaclub.admin.totp.CodigoQr;
import br.com.chimaclub.admin.totp.ServicoTotp;
import br.com.chimaclub.comum.ControladorDoPainel;
import br.com.chimaclub.config.FiltroDeSegundoFator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Ativação e conferência do segundo fator.
 *
 * Responde apenas na porta administrativa, e só para quem já passou pela
 * senha. O segredo em claro aparece nesta tela — é o preço de poder cadastrá-lo
 * num aplicativo autenticador — e por isso ela não existe em lugar nenhum
 * que não exija sessão.
 */
@ControladorDoPainel
public class TotpController {

    private static final String SESSAO_SEGREDO_PENDENTE = "chimaclub.totp-pendente";

    private final ServicoTotp totp;
    private final UsuarioAdminRepository usuarios;
    private final AuditoriaService auditoria;

    public TotpController(ServicoTotp totp, UsuarioAdminRepository usuarios, AuditoriaService auditoria) {
        this.totp = totp;
        this.usuarios = usuarios;
        this.auditoria = auditoria;
    }

    @GetMapping("/admin/totp/conferir")
    public String telaDeConferencia(Principal principal, HttpSession sessao, Model modelo) {
        UsuarioAdmin admin = exigirUsuario(principal);

        // Quem ainda não ativou não tem o que conferir: vai ativar.
        if (!admin.isTotpAtivo()) {
            return "redirect:/admin/totp/ativar";
        }
        modelo.addAttribute("nome", admin.getNome());
        return "admin/totp-conferir";
    }

    @PostMapping("/admin/totp/conferir")
    public String conferir(@RequestParam String codigo, Principal principal,
                           HttpSession sessao, HttpServletRequest requisicao) {

        UsuarioAdmin admin = exigirUsuario(principal);

        if (!totp.confere(admin, codigo)) {
            auditoria.registrar(Acao.LOGIN_FALHA, admin,
                    Map.of("etapa", "segundo_fator"), requisicao);
            return "redirect:/admin/totp/conferir?erro";
        }

        sessao.setAttribute(FiltroDeSegundoFator.SESSAO_SEGUNDO_FATOR_OK, Boolean.TRUE);
        auditoria.registrar(Acao.LOGIN_OK, admin, Map.of("etapa", "segundo_fator"), requisicao);
        return "redirect:/admin/produtos";
    }

    @GetMapping("/admin/totp/ativar")
    public String telaDeAtivacao(Principal principal, HttpSession sessao, Model modelo) {
        UsuarioAdmin admin = exigirUsuario(principal);

        // O segredo fica na sessão até ser confirmado. Gravá-lo no usuário
        // antes da confirmação deixaria a conta com um segundo fator que a
        // pessoa não consegue apresentar — e sem ninguém para destravar.
        String segredo = (String) sessao.getAttribute(SESSAO_SEGREDO_PENDENTE);
        if (segredo == null) {
            segredo = totp.gerarSegredo();
            sessao.setAttribute(SESSAO_SEGREDO_PENDENTE, segredo);
        }

        modelo.addAttribute("segredo", segredo);
        modelo.addAttribute("jaAtivo", admin.isTotpAtivo());
        return "admin/totp-ativar";
    }

    /**
     * O código QR do segredo pendente.
     *
     * Servido como imagem, de rota própria, e não embutido na página: a CSP
     * permite img-src 'self', e uma imagem em data: URI dentro do HTML
     * deixaria o segredo no cache de página e no histórico do navegador.
     */
    @GetMapping(value = "/admin/totp/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> codigoQr(Principal principal, HttpSession sessao) {
        UsuarioAdmin admin = exigirUsuario(principal);
        String segredo = (String) sessao.getAttribute(SESSAO_SEGREDO_PENDENTE);

        if (segredo == null) {
            throw new ResponseStatusException(NOT_FOUND);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(CodigoQr.png(totp.uriDeCadastro(admin.getEmail(), segredo)));
    }

    @PostMapping("/admin/totp/ativar")
    public String ativar(@RequestParam String codigo, Principal principal,
                         HttpSession sessao, HttpServletRequest requisicao) {

        UsuarioAdmin admin = exigirUsuario(principal);
        String segredo = (String) sessao.getAttribute(SESSAO_SEGREDO_PENDENTE);

        if (segredo == null) {
            return "redirect:/admin/totp/ativar";
        }

        // Só ativa se a pessoa conseguir apresentar um código daquele
        // segredo: é a prova de que o autenticador foi mesmo cadastrado.
        if (!totp.confereSegredo(segredo, codigo)) {
            return "redirect:/admin/totp/ativar?erro";
        }

        totp.ativar(admin, segredo);
        usuarios.save(admin);
        sessao.removeAttribute(SESSAO_SEGREDO_PENDENTE);
        sessao.setAttribute(FiltroDeSegundoFator.SESSAO_SEGUNDO_FATOR_OK, Boolean.TRUE);

        auditoria.registrar(Acao.TOTP_ATIVADO, admin, Map.of(), requisicao);
        return "redirect:/admin/produtos";
    }

    private UsuarioAdmin exigirUsuario(Principal principal) {
        return Optional.ofNullable(principal)
                .flatMap(p -> usuarios.findByEmailIgnoreCase(p.getName()))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
    }
}
