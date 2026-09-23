package br.com.chimaclub.midia;

import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reescreve a imagem enviada em três tamanhos.
 *
 * A reescrita é o controle de segurança central do upload, e não um detalhe
 * de apresentação: a saída é construída a partir dos pixels já
 * decodificados, e nada do arquivo original é copiado. Isso descarta, por
 * construção, todos os metadados — EXIF com coordenadas de onde a foto foi
 * tirada, perfis de cor, comentários — e neutraliza a imagem poliglota, que
 * é um JPEG válido com código executável grudado no fim.
 */
@Component
public class ProcessadorImagem {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessadorImagem.class);

    public static final String MINI = "mini";
    public static final String MEDIA = "media";
    public static final String GRANDE = "grande";

    /** Larguras da §3.5: grade da home, carrossel e foto em destaque. */
    private static final Map<String, Integer> LARGURAS = Map.of(
            MINI, 600,
            MEDIA, 1200,
            GRANDE, 2000);

    private static final float QUALIDADE = 0.82f;

    private final String formatoDeSaida;

    public ProcessadorImagem() {
        this.formatoDeSaida = escolherFormatoDeSaida();
    }

    /**
     * WebP quando a nativa funciona; JPEG progressivo quando não.
     *
     * A conferência é uma gravação de verdade, e não a simples presença do
     * escritor no registro do ImageIO. A diferença não é acadêmica: num
     * contêiner com /tmp montado como noexec — que é o nosso caso, de
     * propósito, para que um arquivo de upload que aterrisse lá não possa
     * ser executado — a nativa do WebP se registra normalmente e só falha
     * na hora de mapear a biblioteca, já dentro da primeira gravação. O
     * resultado era um upload que morria com erro interno em vez de recuar.
     *
     * A garantia de segurança vem de reescrever a imagem, não do formato de
     * saída, então recuar para JPEG é perfeitamente aceitável.
     */
    private static String escolherFormatoDeSaida() {
        if (!ImageIO.getImageWritersByFormatName("webp").hasNext()) {
            LOG.warn("escritor de WebP não registrado nesta JVM; as fotos serão gravadas em JPEG");
            return "jpg";
        }

        try {
            BufferedImage teste = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            if (!ImageIO.write(teste, "webp", saida) || saida.size() == 0) {
                throw new IOException("o escritor de WebP não produziu bytes");
            }
            LOG.info("escritor de WebP conferido na partida: as fotos serão gravadas em WebP");
            return "webp";

        } catch (IOException | RuntimeException | LinkageError falha) {
            // LinkageError entra aqui de propósito: UnsatisfiedLinkError e
            // NoClassDefFoundError não são Exception, e sem capturá-los a
            // aplicação subiria achando que sabe gravar WebP.
            LOG.warn("o escritor de WebP não funciona neste ambiente ({}); "
                     + "as fotos serão gravadas em JPEG", falha.getMessage());
            return "jpg";
        }
    }

    public String formato() {
        return formatoDeSaida;
    }

    public String extensao() {
        return formatoDeSaida.equals("webp") ? "webp" : "jpg";
    }

    public String tipoMime() {
        return formatoDeSaida.equals("webp") ? "image/webp" : "image/jpeg";
    }

    public Map<String, byte[]> processar(BufferedImage original) {
        Map<String, byte[]> versoes = new LinkedHashMap<>();
        for (String versao : new String[]{MINI, MEDIA, GRANDE}) {
            versoes.put(versao, redimensionar(original, LARGURAS.get(versao)));
        }
        return versoes;
    }

    private byte[] redimensionar(BufferedImage original, int larguraAlvo) {
        try {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();

            // Uma imagem menor que o alvo não é ampliada: esticar não
            // acrescenta detalhe, só peso.
            int largura = Math.min(larguraAlvo, original.getWidth());
            int altura = Math.max(1, Math.round(
                    largura * (original.getHeight() / (float) original.getWidth())));

            Thumbnails.of(original)
                      .size(largura, altura)
                      .outputFormat(formatoDeSaida)
                      .outputQuality(QUALIDADE)
                      .toOutputStream(saida);

            return saida.toByteArray();
        } catch (IOException falha) {
            throw new UploadRecusadoException("Não foi possível processar a imagem enviada.");
        }
    }
}
