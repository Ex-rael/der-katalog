package br.com.chimaclub.catalogo.web;

import br.com.chimaclub.admin.UsuarioAdmin;
import br.com.chimaclub.admin.UsuarioAdminRepository;
import br.com.chimaclub.catalogo.CategoriaRepository;
import br.com.chimaclub.catalogo.Produto;
import br.com.chimaclub.catalogo.ProdutoRepository;
import br.com.chimaclub.catalogo.dto.ProdutoForm;
import br.com.chimaclub.catalogo.service.FotoService;
import br.com.chimaclub.catalogo.service.ProdutoService;
import br.com.chimaclub.comum.Preco;
import br.com.chimaclub.comum.RegraDeNegocioException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import br.com.chimaclub.comum.ControladorDoPainel;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Painel de produtos. Responde apenas na porta administrativa: a cadeia
 * pública nega /admin/** antes de qualquer roteamento.
 */
@ControladorDoPainel
public class AdminProdutoController {

    private final ProdutoService servico;
    private final FotoService fotoService;
    private final ProdutoRepository produtos;
    private final CategoriaRepository categorias;
    private final UsuarioAdminRepository usuarios;

    public AdminProdutoController(ProdutoService servico, FotoService fotoService,
                                  ProdutoRepository produtos, CategoriaRepository categorias,
                                  UsuarioAdminRepository usuarios) {
        this.servico = servico;
        this.fotoService = fotoService;
        this.produtos = produtos;
        this.categorias = categorias;
        this.usuarios = usuarios;
    }

    @GetMapping("/admin/produtos")
    public String listar(@RequestParam(required = false) String q,
                         @RequestParam(required = false) String situacao,
                         Model modelo) {

        List<Produto> lista = produtos.findAll().stream()
                .filter(produto -> produto.getExcluidoEm() == null)
                .filter(produto -> q == null || q.isBlank()
                        || produto.getNome().toLowerCase().contains(q.toLowerCase()))
                .filter(produto -> situacao == null || situacao.isBlank()
                        || ("publicado".equals(situacao) == produto.isPublicado()))
                .sorted((a, b) -> b.getCriadoEm().compareTo(a.getCriadoEm()))
                .toList();

        modelo.addAttribute("produtos", lista);
        modelo.addAttribute("q", q);
        modelo.addAttribute("situacao", situacao);
        return "admin/produtos";
    }

    @GetMapping("/admin/produtos/novo")
    public String formularioDeCadastro(Model modelo) {
        modelo.addAttribute("form", new ProdutoForm());
        modelo.addAttribute("categorias", categorias.findAllByOrderByOrdemAsc());
        modelo.addAttribute("novo", true);
        return "admin/produto-form";
    }

    @PostMapping("/admin/produtos/novo")
    public String cadastrar(@Valid @org.springframework.web.bind.annotation.ModelAttribute("form") ProdutoForm form,
                            BindingResult erros, Model modelo, Principal principal,
                            HttpServletRequest requisicao, RedirectAttributes redirecionamento) {

        if (erros.hasErrors()) {
            return voltarAoFormulario(modelo, form, true);
        }
        try {
            UUID id = servico.criar(form, autor(principal), requisicao);
            redirecionamento.addFlashAttribute("aviso", "Produto cadastrado.");
            return "redirect:/admin/produtos/" + id;
        } catch (RegraDeNegocioException recusa) {
            erros.reject("regra", recusa.getMessage());
            return voltarAoFormulario(modelo, form, true);
        }
    }

    @GetMapping("/admin/produtos/{id}")
    public String formularioDeEdicao(@PathVariable UUID id, Model modelo) {
        Produto produto = servico.buscarAtivo(id);

        ProdutoForm form = new ProdutoForm();
        form.setNome(produto.getNome());
        form.setDescricao(produto.getDescricao());
        form.setPrecoEmReais(Preco.paraCampo(produto.getPrecoCentavos()));
        form.setUnidades(produto.getUnidades());
        form.setPublicado(produto.isPublicado());
        form.setDestaque(produto.isDestaque());
        form.setOrdem(produto.getOrdem());
        form.setVersao(produto.getVersao());
        if (produto.getCategoria() != null) {
            form.setCategoriaId(produto.getCategoria().getId());
        }

        modelo.addAttribute("form", form);
        modelo.addAttribute("produto", produto);
        modelo.addAttribute("fotos", fotoService.doProduto(id));
        modelo.addAttribute("categorias", categorias.findAllByOrderByOrdemAsc());
        modelo.addAttribute("novo", false);
        return "admin/produto-form";
    }

    @PostMapping("/admin/produtos/{id}")
    public String alterar(@PathVariable UUID id,
                          @Valid @org.springframework.web.bind.annotation.ModelAttribute("form") ProdutoForm form,
                          BindingResult erros, Model modelo, Principal principal,
                          HttpServletRequest requisicao, RedirectAttributes redirecionamento) {

        if (erros.hasErrors()) {
            return voltarAoFormularioDeEdicao(modelo, form, id);
        }
        try {
            servico.alterar(id, form, autor(principal), requisicao);
            redirecionamento.addFlashAttribute("aviso", "Alterações gravadas.");
            return "redirect:/admin/produtos/" + id;
        } catch (RegraDeNegocioException recusa) {
            erros.reject("regra", recusa.getMessage());
            return voltarAoFormularioDeEdicao(modelo, form, id);
        }
    }

    @PostMapping("/admin/produtos/{id}/publicar")
    public String publicar(@PathVariable UUID id, @RequestParam boolean publicar,
                           Principal principal, HttpServletRequest requisicao,
                           RedirectAttributes redirecionamento) {
        try {
            servico.publicar(id, publicar, autor(principal), requisicao);
            redirecionamento.addFlashAttribute("aviso", publicar ? "Produto publicado." : "Produto despublicado.");
        } catch (RegraDeNegocioException recusa) {
            redirecionamento.addFlashAttribute("erro", recusa.getMessage());
        }
        return "redirect:/admin/produtos";
    }

    @PostMapping("/admin/produtos/{id}/excluir")
    public String excluir(@PathVariable UUID id, Principal principal,
                          HttpServletRequest requisicao, RedirectAttributes redirecionamento) {
        servico.excluir(id, autor(principal), requisicao);
        redirecionamento.addFlashAttribute("aviso", "Produto excluído. Ele sai do catálogo, mas fica no histórico.");
        return "redirect:/admin/produtos";
    }

    @PostMapping("/admin/produtos/{id}/fotos")
    public String enviarFotos(@PathVariable UUID id,
                              @RequestParam("arquivos") MultipartFile[] arquivos,
                              Principal principal, HttpServletRequest requisicao,
                              RedirectAttributes redirecionamento) {

        if (arquivos.length > FotoService.MAXIMO_POR_ENVIO) {
            redirecionamento.addFlashAttribute("erro",
                    "Envie no máximo " + FotoService.MAXIMO_POR_ENVIO + " fotos por vez.");
            return "redirect:/admin/produtos/" + id;
        }

        int enviadas = 0;
        for (MultipartFile arquivo : arquivos) {
            if (arquivo.isEmpty()) {
                continue;
            }
            fotoService.enviar(id, arquivo, autor(principal), requisicao);
            enviadas++;
        }
        redirecionamento.addFlashAttribute("aviso", enviadas + " foto(s) enviada(s).");
        return "redirect:/admin/produtos/" + id;
    }

    @PostMapping("/admin/produtos/{id}/fotos/{fotoId}/excluir")
    public String excluirFoto(@PathVariable UUID id, @PathVariable UUID fotoId,
                              Principal principal, HttpServletRequest requisicao,
                              RedirectAttributes redirecionamento) {
        fotoService.excluir(id, fotoId, autor(principal), requisicao);
        redirecionamento.addFlashAttribute("aviso", "Foto excluída.");
        return "redirect:/admin/produtos/" + id;
    }

    @PostMapping("/admin/produtos/{id}/fotos/{fotoId}/principal")
    public String definirPrincipal(@PathVariable UUID id, @PathVariable UUID fotoId,
                                   Principal principal, HttpServletRequest requisicao,
                                   RedirectAttributes redirecionamento) {
        fotoService.definirPrincipal(id, fotoId, autor(principal), requisicao);
        redirecionamento.addFlashAttribute("aviso", "Foto principal definida.");
        return "redirect:/admin/produtos/" + id;
    }

    private String voltarAoFormulario(Model modelo, ProdutoForm form, boolean novo) {
        modelo.addAttribute("categorias", categorias.findAllByOrderByOrdemAsc());
        modelo.addAttribute("novo", novo);
        return "admin/produto-form";
    }

    private String voltarAoFormularioDeEdicao(Model modelo, ProdutoForm form, UUID id) {
        modelo.addAttribute("produto", servico.buscarAtivo(id));
        modelo.addAttribute("fotos", fotoService.doProduto(id));
        return voltarAoFormulario(modelo, form, false);
    }

    private UsuarioAdmin autor(Principal principal) {
        if (principal == null) {
            return null;
        }
        return usuarios.findByEmailIgnoreCase(principal.getName()).orElse(null);
    }
}
