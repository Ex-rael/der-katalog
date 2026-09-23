package br.com.chimaclub.catalogo.service;

import br.com.chimaclub.admin.Acao;
import br.com.chimaclub.admin.AuditoriaService;
import br.com.chimaclub.admin.UsuarioAdmin;
import br.com.chimaclub.catalogo.Produto;
import br.com.chimaclub.catalogo.ProdutoFoto;
import br.com.chimaclub.catalogo.ProdutoFotoRepository;
import br.com.chimaclub.catalogo.ProdutoRepository;
import br.com.chimaclub.comum.GeradorDeId;
import br.com.chimaclub.comum.RegraDeNegocioException;
import br.com.chimaclub.midia.ArmazenamentoFotos;
import br.com.chimaclub.midia.ProcessadorImagem;
import br.com.chimaclub.midia.ValidadorUpload;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Regras 5 e 6 da §6: foto principal automática e limpeza na exclusão. */
@Service
public class FotoService {

    /** 8 arquivos por requisição, como declara a §A04. */
    public static final int MAXIMO_POR_ENVIO = 8;

    private final ProdutoRepository produtos;
    private final ProdutoFotoRepository fotos;
    private final ValidadorUpload validador;
    private final ProcessadorImagem processador;
    private final ArmazenamentoFotos armazenamento;
    private final AuditoriaService auditoria;

    public FotoService(ProdutoRepository produtos, ProdutoFotoRepository fotos,
                       ValidadorUpload validador, ProcessadorImagem processador,
                       ArmazenamentoFotos armazenamento, AuditoriaService auditoria) {
        this.produtos = produtos;
        this.fotos = fotos;
        this.validador = validador;
        this.processador = processador;
        this.armazenamento = armazenamento;
        this.auditoria = auditoria;
    }

    @Transactional
    public UUID enviar(UUID produtoId, MultipartFile arquivo,
                       UsuarioAdmin autor, HttpServletRequest requisicao) {

        Produto produto = produtos.findById(produtoId)
                .filter(p -> p.getExcluidoEm() == null)
                .orElseThrow(() -> new RegraDeNegocioException("Produto não encontrado."));

        // Validar antes de qualquer gravação: um arquivo recusado não pode
        // deixar rastro nem no disco nem no banco.
        BufferedImage imagem = validador.validar(arquivo).imagem();
        Map<String, byte[]> versoes = processador.processar(imagem);

        UUID base = GeradorDeId.novo();
        Map<String, String> nomes = armazenamento.gravar(base, versoes, processador.extensao());

        ProdutoFoto foto = new ProdutoFoto(produto,
                nomes.get(ProcessadorImagem.MEDIA),
                nomes.get(ProcessadorImagem.MINI),
                imagem.getWidth(), imagem.getHeight(),
                versoes.get(ProcessadorImagem.MEDIA).length,
                processador.tipoMime());

        // §6.5: a primeira foto enviada vira principal automaticamente.
        boolean ehAPrimeira = fotos.countByProdutoId(produtoId) == 0;
        foto.setPrincipal(ehAPrimeira);
        foto.setOrdem((int) fotos.countByProdutoId(produtoId));

        UUID id = fotos.save(foto).getId();
        auditoria.registrar(Acao.FOTO_ENVIADA, autor, "produto_foto", id,
                Map.of("produto_id", produtoId.toString(), "principal", ehAPrimeira), requisicao);
        return id;
    }

    @Transactional
    public void excluir(UUID produtoId, UUID fotoId, UsuarioAdmin autor, HttpServletRequest requisicao) {
        ProdutoFoto foto = fotos.findById(fotoId)
                .filter(f -> f.getProduto().getId().equals(produtoId))
                .orElseThrow(() -> new RegraDeNegocioException("Foto não encontrada."));

        boolean eraAPrincipal = foto.isPrincipal();
        String arquivoMedia = foto.getArquivo();
        String arquivoMini = foto.getArquivoMini();

        fotos.delete(foto);
        fotos.flush();

        // §6.5: ao excluir a principal, a próxima na ordem assume. Sem isto,
        // o produto ficaria sem foto de capa e sumiria da grade da home.
        if (eraAPrincipal) {
            fotos.findByProdutoIdOrderByOrdemAsc(produtoId).stream()
                 .min(Comparator.comparingInt(ProdutoFoto::getOrdem))
                 .ifPresent(proxima -> {
                     proxima.setPrincipal(true);
                     fotos.save(proxima);
                 });
        }

        // §6.3: sem foto nenhuma, o produto não pode seguir publicado.
        if (fotos.countByProdutoId(produtoId) == 0) {
            produtos.findById(produtoId).ifPresent(produto -> {
                if (produto.isPublicado()) {
                    produto.setPublicado(false);
                    produtos.save(produto);
                }
            });
        }

        // O arquivo em disco fica por 30 dias (§6.6), removido pela rotina de
        // limpeza. Apagar aqui impediria desfazer uma exclusão por engano.
        auditoria.registrar(Acao.FOTO_EXCLUIDA, autor, "produto_foto", fotoId,
                Map.of("produto_id", produtoId.toString(),
                       "arquivo", arquivoMedia,
                       "arquivo_mini", arquivoMini,
                       "era_principal", eraAPrincipal),
                requisicao);
    }

    @Transactional
    public void definirPrincipal(UUID produtoId, UUID fotoId, UsuarioAdmin autor,
                                 HttpServletRequest requisicao) {
        List<ProdutoFoto> doProduto = fotos.findByProdutoIdOrderByOrdemAsc(produtoId);
        if (doProduto.stream().noneMatch(foto -> foto.getId().equals(fotoId))) {
            throw new RegraDeNegocioException("Foto não encontrada.");
        }

        // Tira a principal de todas antes de pôr na escolhida: o índice
        // único parcial recusaria duas principais ao mesmo tempo.
        doProduto.forEach(foto -> foto.setPrincipal(false));
        fotos.saveAllAndFlush(doProduto);

        doProduto.stream()
                 .filter(foto -> foto.getId().equals(fotoId))
                 .findFirst()
                 .ifPresent(foto -> {
                     foto.setPrincipal(true);
                     fotos.save(foto);
                 });

        auditoria.registrar(Acao.FOTO_REORDENADA, autor, "produto_foto", fotoId,
                Map.of("produto_id", produtoId.toString(), "acao", "principal"), requisicao);
    }

    @Transactional
    public void reordenar(UUID produtoId, List<UUID> idsNaNovaOrdem, UsuarioAdmin autor,
                          HttpServletRequest requisicao) {
        List<ProdutoFoto> doProduto = fotos.findByProdutoIdOrderByOrdemAsc(produtoId);

        for (int posicao = 0; posicao < idsNaNovaOrdem.size(); posicao++) {
            UUID id = idsNaNovaOrdem.get(posicao);
            int finalPosicao = posicao;
            doProduto.stream()
                     .filter(foto -> foto.getId().equals(id))
                     .findFirst()
                     .ifPresent(foto -> foto.setOrdem(finalPosicao));
        }
        fotos.saveAll(doProduto);

        auditoria.registrar(Acao.FOTO_REORDENADA, autor, "produto", produtoId,
                Map.of("quantidade", idsNaNovaOrdem.size()), requisicao);
    }

    @Transactional(readOnly = true)
    public List<ProdutoFoto> doProduto(UUID produtoId) {
        return fotos.findByProdutoIdOrderByOrdemAsc(produtoId);
    }
}
