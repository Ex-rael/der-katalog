package br.com.chimaclub.catalogo.service;

import br.com.chimaclub.admin.Acao;
import br.com.chimaclub.admin.AuditoriaService;
import br.com.chimaclub.admin.UsuarioAdmin;
import br.com.chimaclub.catalogo.Categoria;
import br.com.chimaclub.catalogo.CategoriaRepository;
import br.com.chimaclub.catalogo.Produto;
import br.com.chimaclub.catalogo.ProdutoFotoRepository;
import br.com.chimaclub.catalogo.ProdutoRepository;
import br.com.chimaclub.catalogo.dto.ProdutoForm;
import br.com.chimaclub.comum.Preco;
import br.com.chimaclub.comum.RegraDeNegocioException;
import br.com.chimaclub.comum.Slugify;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** As regras da §6 do documento de projeto. */
@Service
public class ProdutoService {

    private final ProdutoRepository produtos;
    private final ProdutoFotoRepository fotos;
    private final CategoriaRepository categorias;
    private final AuditoriaService auditoria;

    public ProdutoService(ProdutoRepository produtos, ProdutoFotoRepository fotos,
                          CategoriaRepository categorias, AuditoriaService auditoria) {
        this.produtos = produtos;
        this.fotos = fotos;
        this.categorias = categorias;
        this.auditoria = auditoria;
    }

    @Transactional
    public UUID criar(ProdutoForm form, UsuarioAdmin autor, HttpServletRequest requisicao) {
        Produto produto = new Produto(
                form.getNome().trim(),
                slugDisponivelPara(form.getNome()),
                Preco.paraCentavos(form.getPrecoEmReais()));

        aplicarCamposEditaveis(produto, form);

        // Publicar na criação exige as mesmas condições de sempre.
        if (form.isPublicado()) {
            conferirCondicoesDePublicacao(produto);
        }
        produto.setPublicado(form.isPublicado());

        UUID id = produtos.save(produto).getId();
        auditoria.registrar(Acao.PRODUTO_CRIADO, autor, "produto", id,
                Map.of("nome", produto.getNome(), "preco_centavos", produto.getPrecoCentavos()),
                requisicao);
        return id;
    }

    @Transactional
    public void alterar(UUID id, ProdutoForm form, UsuarioAdmin autor, HttpServletRequest requisicao) {
        Produto produto = buscarAtivo(id);

        // Bloqueio otimista conferido na aplicação, antes de tocar a
        // entidade: assim a recusa vira mensagem para a dona da loja em vez
        // de exceção de infraestrutura na hora do flush.
        if (produto.getVersao() != form.getVersao()) {
            throw new RegraDeNegocioException(
                    "Este produto foi alterado em outro lugar depois que você abriu esta tela. "
                    + "Recarregue para ver a versão atual.");
        }

        Map<String, Object> mudancas = new HashMap<>();
        if (!produto.getNome().equals(form.getNome().trim())) {
            mudancas.put("nome_anterior", produto.getNome());
        }
        long precoNovo = Preco.paraCentavos(form.getPrecoEmReais());
        if (produto.getPrecoCentavos() != precoNovo) {
            mudancas.put("preco_anterior", produto.getPrecoCentavos());
            mudancas.put("preco_novo", precoNovo);
        }

        produto.setNome(form.getNome().trim());
        produto.setPrecoCentavos(precoNovo);
        aplicarCamposEditaveis(produto, form);

        // O slug não muda junto com o nome, de propósito: um link já
        // compartilhado no WhatsApp deixaria de funcionar. A troca existe,
        // mas é ação explícita da tela, não efeito colateral de uma edição
        // — §6.2. Por isso ela só acontece quando o campo vem preenchido e
        // diferente do que já está gravado.
        String slugPedido = form.getSlug() == null ? "" : Slugify.de(form.getSlug());
        if (!slugPedido.isBlank() && !slugPedido.equals(produto.getSlug())) {
            if (produtos.existsBySlug(slugPedido)) {
                throw new RegraDeNegocioException(
                        "Já existe outro produto neste endereço. Escolha um diferente.");
            }
            mudancas.put("slug_anterior", produto.getSlug());
            mudancas.put("slug_novo", slugPedido);
            produto.setSlug(slugPedido);
        }

        if (form.isPublicado() && !produto.isPublicado()) {
            conferirCondicoesDePublicacao(produto);
        }
        produto.setPublicado(form.isPublicado());

        produtos.save(produto);
        auditoria.registrar(Acao.PRODUTO_ALTERADO, autor, "produto", id, mudancas, requisicao);
    }

    /**
     * Duplica um produto (§4.3).
     *
     * Copia os dados e NÃO copia as fotos, de propósito. Duplicar serve para
     * criar a peça irmã — a mesma cuia em outra cor — e nesse caso levar a
     * foto junto poria a imagem errada no produto novo, com boa chance de
     * ninguém notar antes de publicar. Sem foto, a regra de publicação
     * obriga a enviar a certa.
     *
     * A cópia nasce como rascunho pelo mesmo motivo: publicada sozinha, ela
     * apareceria no catálogo com o nome do original.
     */
    @Transactional
    public UUID duplicar(UUID id, UsuarioAdmin autor, HttpServletRequest requisicao) {
        Produto original = buscarAtivo(id);

        String nomeDaCopia = encurtar(original.getNome() + " (cópia)", 140);
        Produto copia = new Produto(nomeDaCopia, slugDisponivelPara(nomeDaCopia),
                original.getPrecoCentavos());

        copia.setDescricao(original.getDescricao());
        copia.setUnidades(original.getUnidades());
        copia.setCategoria(original.getCategoria());
        copia.setOrdem(original.getOrdem());
        copia.setDestaque(false);
        copia.setPublicado(false);

        UUID novoId = produtos.save(copia).getId();
        auditoria.registrar(Acao.PRODUTO_CRIADO, autor, "produto", novoId,
                Map.of("duplicado_de", id.toString(), "nome", nomeDaCopia), requisicao);
        return novoId;
    }

    private static String encurtar(String texto, int limite) {
        return texto.length() <= limite ? texto : texto.substring(0, limite);
    }

    @Transactional
    public void publicar(UUID id, boolean publicar, UsuarioAdmin autor, HttpServletRequest requisicao) {
        Produto produto = buscarAtivo(id);
        if (publicar) {
            conferirCondicoesDePublicacao(produto);
        }
        produto.setPublicado(publicar);
        produtos.save(produto);

        auditoria.registrar(publicar ? Acao.PRODUTO_PUBLICADO : Acao.PRODUTO_DESPUBLICADO,
                autor, "produto", id, Map.of("nome", produto.getNome()), requisicao);
    }

    /** Exclusão sempre lógica: nada some do banco (§6.6). */
    @Transactional
    public void excluir(UUID id, UsuarioAdmin autor, HttpServletRequest requisicao) {
        Produto produto = buscarAtivo(id);
        produto.setExcluidoEm(Instant.now());
        produto.setPublicado(false);
        produtos.save(produto);

        auditoria.registrar(Acao.PRODUTO_EXCLUIDO, autor, "produto", id,
                Map.of("nome", produto.getNome()), requisicao);
    }

    @Transactional(readOnly = true)
    public Produto buscarAtivo(UUID id) {
        return produtos.findById(id)
                .filter(produto -> produto.getExcluidoEm() == null)
                .orElseThrow(() -> new RegraDeNegocioException("Produto não encontrado."));
    }

    /**
     * §6.3: um produto só aparece no catálogo se tiver ao menos uma foto e
     * preço maior que zero. Conferido aqui, e não só na tela, porque a tela
     * pode ser contornada.
     */
    private void conferirCondicoesDePublicacao(Produto produto) {
        if (produto.getPrecoCentavos() <= 0) {
            throw new RegraDeNegocioException(
                    "Para publicar, informe um preço maior que zero.");
        }
        if (produto.getId() != null && fotos.countByProdutoId(produto.getId()) == 0) {
            throw new RegraDeNegocioException(
                    "Para publicar, envie ao menos uma foto do produto.");
        }
    }

    private void aplicarCamposEditaveis(Produto produto, ProdutoForm form) {
        produto.setDescricao(form.getDescricao());
        produto.setUnidades(form.getUnidades());
        produto.setDestaque(form.isDestaque());
        produto.setOrdem(form.getOrdem());

        if (form.getCategoriaId() != null) {
            Categoria categoria = categorias.findById(form.getCategoriaId())
                    .orElseThrow(() -> new RegraDeNegocioException("Categoria não encontrada."));
            produto.setCategoria(categoria);
        } else {
            produto.setCategoria(null);
        }
    }

    /**
     * §6.2: em caso de colisão, acrescenta sufixo numérico. A coluna tem
     * chave única, então esta conferência evita o erro; a chave única
     * garante que nada passe se duas gravações correrem juntas.
     */
    private String slugDisponivelPara(String nome) {
        String base = Slugify.de(nome);
        if (!produtos.existsBySlug(base)) {
            return base;
        }
        for (int sufixo = 2; sufixo < 1000; sufixo++) {
            String candidato = base + "-" + sufixo;
            if (!produtos.existsBySlug(candidato)) {
                return candidato;
            }
        }
        throw new RegraDeNegocioException(
                "Já existem produtos demais com este nome. Escolha um nome diferente.");
    }
}
