package br.com.chimaclub.midia;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Trata todo upload como hostil (§A08).
 *
 * A ordem das conferências é do mais barato para o mais caro, e não por
 * acaso: recusar por tamanho ou por extensão custa quase nada, e é o que
 * absorve a maior parte das tentativas sem dar ao atacante a chance de
 * consumir memória ou processador.
 */
@Component
public class ValidadorUpload {

    /** Só o que a aplicação consegue reescrever com segurança. */
    private static final List<String> EXTENSOES_PERMITIDAS = List.of("jpg", "jpeg", "png", "webp");

    private static final long BYTES_MAXIMOS = 10L * 1024 * 1024;

    /**
     * 8000 × 8000 em RGB são 256 MB de heap. Acima disso a intenção não é
     * mandar uma foto de cuia.
     */
    private static final int DIMENSAO_MAXIMA = 8000;

    /** A imagem já carregada e o tipo que os bytes iniciais declararam. */
    public record ImagemValidada(BufferedImage imagem, String tipoReal) {
    }

    public ImagemValidada validar(MultipartFile arquivo) {
        if (arquivo == null || arquivo.isEmpty()) {
            throw new UploadRecusadoException("Envie um arquivo de imagem.");
        }
        if (arquivo.getSize() > BYTES_MAXIMOS) {
            throw new UploadRecusadoException("A imagem passa de 10 MB. Envie uma versão menor.");
        }

        String extensao = extensaoDe(arquivo.getOriginalFilename());
        if (!EXTENSOES_PERMITIDAS.contains(extensao)) {
            throw new UploadRecusadoException("Formato não aceito. Envie JPG, PNG ou WebP.");
        }

        byte[] bytes;
        try {
            bytes = arquivo.getBytes();
        } catch (IOException falha) {
            throw new UploadRecusadoException("Não foi possível ler o arquivo enviado.");
        }

        String tipoReal = tipoPelosBytes(bytes);
        if (tipoReal == null) {
            // O nome dizia .jpg, o conteúdo não é imagem. É o caso do .php
            // renomeado, e do .svg, que nem sequer tem assinatura binária.
            throw new UploadRecusadoException("O arquivo enviado não é uma imagem.");
        }

        return new ImagemValidada(carregarComLimiteDeDimensao(bytes), tipoReal);
    }

    private static String extensaoDe(String nomeEnviado) {
        if (nomeEnviado == null || nomeEnviado.isBlank()) {
            throw new UploadRecusadoException("Envie um arquivo de imagem.");
        }
        int ponto = nomeEnviado.lastIndexOf('.');
        if (ponto < 0 || ponto == nomeEnviado.length() - 1) {
            throw new UploadRecusadoException("Formato não aceito. Envie JPG, PNG ou WebP.");
        }
        return nomeEnviado.substring(ponto + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Confere a assinatura dos bytes iniciais. O nome do arquivo e o
     * Content-Type vêm do cliente, e o cliente pode ser hostil; a assinatura
     * é a única evidência que o servidor tem sobre o conteúdo.
     */
    private static String tipoPelosBytes(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * Lê a dimensão do cabeçalho e só então descomprime. É a diferença entre
     * recusar uma bomba de descompressão em milissegundos e tentar alocar
     * gigabytes antes de descobrir o problema.
     */
    private static BufferedImage carregarComLimiteDeDimensao(byte[] bytes) {
        try (ImageInputStream entrada = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> leitores = ImageIO.getImageReaders(entrada);
            if (!leitores.hasNext()) {
                throw new UploadRecusadoException("O arquivo enviado não é uma imagem.");
            }

            ImageReader leitor = leitores.next();
            try {
                leitor.setInput(entrada, true, true);

                int largura = leitor.getWidth(0);
                int altura = leitor.getHeight(0);
                if (largura > DIMENSAO_MAXIMA || altura > DIMENSAO_MAXIMA) {
                    throw new UploadRecusadoException(
                            "Imagem com dimensão acima do limite de %d × %d pixels."
                                    .formatted(DIMENSAO_MAXIMA, DIMENSAO_MAXIMA));
                }

                BufferedImage imagem = leitor.read(0);
                if (imagem == null) {
                    throw new UploadRecusadoException("O arquivo enviado não é uma imagem.");
                }
                return imagem;
            } finally {
                leitor.dispose();
            }
        } catch (UploadRecusadoException recusa) {
            throw recusa;
        } catch (IOException | RuntimeException falha) {
            // Um arquivo malformado faz o decodificador lançar de várias
            // maneiras. Todas significam a mesma coisa para quem enviou.
            throw new UploadRecusadoException("O arquivo enviado não é uma imagem válida.");
        }
    }
}
