package br.com.chimaclub.midia;

import br.com.chimaclub.catalogo.ProdutoFoto;
import br.com.chimaclub.catalogo.ProdutoFotoRepository;
import br.com.chimaclub.catalogo.ProdutoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Apaga do disco as fotos de produtos excluídos há mais de trinta dias
 * (§6.6 do documento de projeto).
 *
 * Os trinta dias existem para dar tempo de desfazer um engano: a exclusão é
 * lógica justamente para que um clique errado não seja definitivo, e apagar
 * o arquivo junto acabaria com essa margem. Passado o prazo, o arquivo não
 * serve a ninguém e só ocupa espaço e backup.
 */
@Component
public class LimpezaDeFotos {

    private static final Logger LOG = LoggerFactory.getLogger(LimpezaDeFotos.class);

    public static final Duration PRAZO = Duration.ofDays(30);

    private final ProdutoRepository produtos;
    private final ProdutoFotoRepository fotos;
    private final ArmazenamentoFotos armazenamento;

    public LimpezaDeFotos(ProdutoRepository produtos, ProdutoFotoRepository fotos,
                          ArmazenamentoFotos armazenamento) {
        this.produtos = produtos;
        this.fotos = fotos;
        this.armazenamento = armazenamento;
    }

    /** Às quatro da manhã, uma hora depois do backup. */
    @Scheduled(cron = "0 0 4 * * *")
    public void rotinaDiaria() {
        int apagadas = executar();
        if (apagadas > 0) {
            LOG.info("limpeza de fotos: {} arquivo(s) de produtos excluídos há mais de {} dias",
                    apagadas, PRAZO.toDays());
        }
    }

    /** Devolve quantos arquivos foram apagados. Separado da rotina para o teste. */
    @Transactional
    public int executar() {
        Instant limite = Instant.now().minus(PRAZO);
        int apagados = 0;

        List<ProdutoFoto> candidatas = produtos.findAll().stream()
                .filter(produto -> produto.getExcluidoEm() != null)
                .filter(produto -> produto.getExcluidoEm().isBefore(limite))
                .flatMap(produto -> fotos.findByProdutoIdOrderByOrdemAsc(produto.getId()).stream())
                .toList();

        for (ProdutoFoto foto : candidatas) {
            apagados += apagarArquivosDe(foto);
            fotos.delete(foto);
        }
        return apagados;
    }

    /**
     * O nome guardado é o da versão média; as outras duas saem do mesmo
     * identificador. Um arquivo já ausente não é erro — pode ter sido
     * removido à mão, ou a rotina pode ter parado no meio de uma execução
     * anterior — e derrubar a limpeza por causa disso deixaria todo o resto
     * acumulando para sempre.
     */
    private int apagarArquivosDe(ProdutoFoto foto) {
        int apagados = 0;
        String base = foto.getArquivo().replaceAll("-(mini|media|grande)\\.(webp|jpg)$", "");
        String extensao = foto.getArquivo().endsWith(".webp") ? "webp" : "jpg";

        for (String versao : new String[]{"mini", "media", "grande"}) {
            String nome = base + "-" + versao + "." + extensao;
            try {
                if (armazenamento.existe(nome)) {
                    armazenamento.apagar(nome);
                    apagados++;
                }
            } catch (RuntimeException falha) {
                LOG.warn("não foi possível apagar {}; a limpeza segue com os demais", nome);
            }
        }
        return apagados;
    }
}
