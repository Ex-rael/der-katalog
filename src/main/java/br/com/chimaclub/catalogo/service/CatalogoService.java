package br.com.chimaclub.catalogo.service;

import br.com.chimaclub.catalogo.Categoria;
import br.com.chimaclub.catalogo.CategoriaRepository;
import br.com.chimaclub.catalogo.Produto;
import br.com.chimaclub.catalogo.ProdutoFotoRepository;
import br.com.chimaclub.catalogo.ProdutoRepository;
import br.com.chimaclub.catalogo.dto.ProdutoDetalhe;
import br.com.chimaclub.catalogo.dto.ProdutoResumo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** O que o catálogo público enxerga. Nada aqui depende de sessão. */
@Service
public class CatalogoService {

    private final ProdutoRepository produtos;
    private final ProdutoFotoRepository fotos;
    private final CategoriaRepository categorias;

    public CatalogoService(ProdutoRepository produtos, ProdutoFotoRepository fotos,
                           CategoriaRepository categorias) {
        this.produtos = produtos;
        this.fotos = fotos;
        this.categorias = categorias;
    }

    @Transactional(readOnly = true)
    public List<ProdutoResumo> publicados() {
        return produtos.listarPublicados();
    }

    /**
     * Busca por nome, tolerante a acento, caixa e erro de digitação.
     *
     * O termo vai para a consulta como parâmetro vinculado; os caracteres
     * especiais do LIKE são escapados antes. Isso não é proteção contra
     * injeção — o parâmetro vinculado já cuida disso — e sim correção de
     * resultado: sem escapar, buscar por "%" devolveria o catálogo inteiro,
     * e buscar por "100%" não acharia o produto chamado "100%".
     */
    @Transactional(readOnly = true)
    public List<ProdutoResumo> buscar(String termo) {
        if (termo == null || termo.isBlank()) {
            return publicados();
        }
        return produtos.buscarPublicados(escaparCuringas(termo.trim()), termo.trim());
    }

    /** Agrupa na ordem das categorias, com os sem categoria por último. */
    @Transactional(readOnly = true)
    public Map<String, List<ProdutoResumo>> agrupadosPorCategoria(List<ProdutoResumo> lista) {
        Map<String, List<ProdutoResumo>> grupos = new LinkedHashMap<>();

        for (Categoria categoria : categorias.findAllByOrderByOrdemAsc()) {
            List<ProdutoResumo> doGrupo = lista.stream()
                    .filter(produto -> categoria.getId().equals(produto.categoriaId()))
                    .toList();
            if (!doGrupo.isEmpty()) {
                grupos.put(categoria.getNome(), doGrupo);
            }
        }

        List<ProdutoResumo> semCategoria = lista.stream()
                .filter(produto -> produto.categoriaId() == null)
                .toList();
        if (!semCategoria.isEmpty()) {
            grupos.put("Outros artefatos", semCategoria);
        }
        return grupos;
    }

    @Transactional(readOnly = true)
    public Optional<ProdutoDetalhe> detalhe(String slug) {
        return produtos.findBySlugAndExcluidoEmIsNull(slug)
                .filter(Produto::isPublicado)
                .map(produto -> new ProdutoDetalhe(
                        produto.getId(),
                        produto.getNome(),
                        produto.getSlug(),
                        produto.getDescricao(),
                        produto.getPrecoCentavos(),
                        produto.getUnidades(),
                        produto.getCategoria() == null ? null : produto.getCategoria().getId(),
                        produto.getCategoria() == null ? null : produto.getCategoria().getNome(),
                        fotos.findByProdutoIdOrderByOrdemAsc(produto.getId())));
    }

    /** Outros produtos da mesma categoria, sem repetir o que está aberto. */
    @Transactional(readOnly = true)
    public List<ProdutoResumo> relacionados(ProdutoDetalhe aberto, int quantidade) {
        if (aberto.categoriaId() == null) {
            return List.of();
        }
        return produtos.listarPublicados().stream()
                .filter(produto -> aberto.categoriaId().equals(produto.categoriaId()))
                .filter(produto -> !produto.id().equals(aberto.id()))
                .limit(quantidade)
                .toList();
    }

    /**
     * No LIKE do PostgreSQL, "%" casa qualquer sequência e "_" qualquer
     * caractere. Vindos do termo de busca, eles precisam ser literais.
     */
    private static String escaparCuringas(String termo) {
        return termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
