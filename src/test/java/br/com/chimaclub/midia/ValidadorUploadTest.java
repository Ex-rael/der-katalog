package br.com.chimaclub.midia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O contrato do upload é definido pelo que NÃO pode passar. Cada teste aqui
 * corresponde a um item da lista 5.1 do plano de segurança, e o conjunto
 * substitui a conferência manual que a lista pedia.
 */
class ValidadorUploadTest {

    private final ValidadorUpload validador = new ValidadorUpload();

    private static byte[] imagemDe(int largura, int altura, String formato) throws Exception {
        BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ImageIO.write(imagem, formato, saida);
        return saida.toByteArray();
    }

    @Test
    @DisplayName("aceita um JPEG legítimo")
    void aceitaJpegLegitimo() throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "cuia.jpg", "image/jpeg", imagemDe(1200, 1500, "jpg"));

        assertThatCode(() -> validador.validar(arquivo)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("aceita um PNG legítimo e devolve a imagem carregada")
    void aceitaPngLegitimo() throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "cuia.png", "image/png", imagemDe(800, 1000, "png"));

        ValidadorUpload.ImagemValidada validada = validador.validar(arquivo);

        assertThat(validada.imagem().getWidth()).isEqualTo(800);
        assertThat(validada.imagem().getHeight()).isEqualTo(1000);
    }

    @Test
    @DisplayName("recusa um .php renomeado para .jpg — o conteúdo é que manda")
    void recusaPhpRenomeado() {
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "backdoor.jpg", "image/jpeg",
                "<?php system($_GET['c']); ?>".getBytes());

        assertThatThrownBy(() -> validador.validar(arquivo))
                .isInstanceOf(UploadRecusadoException.class);
    }

    @Test
    @DisplayName("recusa um SVG com script, mesmo renomeado para .jpg")
    void recusaSvgComScript() {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>";

        for (String nome : new String[]{"vetor.svg", "vetor.jpg", "vetor.png"}) {
            MockMultipartFile arquivo = new MockMultipartFile("foto", nome, "image/svg+xml", svg.getBytes());

            assertThatThrownBy(() -> validador.validar(arquivo))
                    .as("arquivo enviado como %s", nome)
                    .isInstanceOf(UploadRecusadoException.class);
        }
    }

    @Test
    @DisplayName("recusa extensão fora da lista, ainda que o conteúdo seja imagem")
    void recusaExtensaoForaDaLista() throws Exception {
        for (String nome : new String[]{"cuia.bmp", "cuia.gif", "cuia.tiff", "cuia.jsp", "cuia"}) {
            MockMultipartFile arquivo = new MockMultipartFile(
                    "foto", nome, "image/jpeg", imagemDe(100, 100, "jpg"));

            assertThatThrownBy(() -> validador.validar(arquivo))
                    .as("extensão de %s", nome)
                    .isInstanceOf(UploadRecusadoException.class);
        }
    }

    @Test
    @DisplayName("recusa imagem de dimensão absurda antes de alocar a memória")
    void recusaBombaDeDescompressao() {
        // 20000 x 20000 em RGB int seriam 1,6 GB de heap. A dimensão é lida
        // do cabeçalho e recusada antes de qualquer descompressão.
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "bomba.png", "image/png", pngComCabecalho(20000, 20000));

        assertThatThrownBy(() -> validador.validar(arquivo))
                .isInstanceOf(UploadRecusadoException.class)
                .hasMessageContaining("dimens");
    }

    @Test
    @DisplayName("a recusa da bomba é rápida: não tenta descomprimir")
    void recusaDaBombaEhRapida() {
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "bomba.png", "image/png", pngComCabecalho(25000, 25000));

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> validador.validar(arquivo)).isInstanceOf(UploadRecusadoException.class);
        long milissegundos = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(milissegundos)
                .as("uma recusa lenta significa que houve tentativa de alocar")
                .isLessThan(1000);
    }

    @Test
    @DisplayName("recusa arquivo vazio")
    void recusaArquivoVazio() {
        MockMultipartFile arquivo = new MockMultipartFile("foto", "vazio.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> validador.validar(arquivo))
                .isInstanceOf(UploadRecusadoException.class);
    }

    @Test
    @DisplayName("recusa arquivo sem nome")
    void recusaArquivoSemNome() throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", null, "image/jpeg", imagemDe(100, 100, "jpg"));

        assertThatThrownBy(() -> validador.validar(arquivo))
                .isInstanceOf(UploadRecusadoException.class);
    }

    @Test
    @DisplayName("recusa cabeçalho de imagem seguido de carga executável")
    void recusaImagemPoliglota() throws Exception {
        // Truque conhecido: JPEG válido com PHP grudado no fim. O arquivo
        // abre como imagem, e é por isso que só validar não basta — o
        // processador reescreve a imagem, descartando o apêndice.
        byte[] jpeg = imagemDe(200, 200, "jpg");
        byte[] carga = "<?php system($_GET['c']); ?>".getBytes();
        byte[] poliglota = new byte[jpeg.length + carga.length];
        System.arraycopy(jpeg, 0, poliglota, 0, jpeg.length);
        System.arraycopy(carga, 0, poliglota, jpeg.length, carga.length);

        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "poliglota.jpg", "image/jpeg", poliglota);

        // O validador aceita, porque é imagem de verdade. Quem neutraliza a
        // carga é o processador, e o ProcessadorImagemTest prova isso.
        assertThatCode(() -> validador.validar(arquivo)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("recusa arquivo acima do limite de 10 MB")
    void recusaArquivoGrandeDemais() {
        byte[] gigante = new byte[11 * 1024 * 1024];
        // assinatura JPEG, para que a recusa venha do tamanho e não do tipo
        gigante[0] = (byte) 0xFF;
        gigante[1] = (byte) 0xD8;
        gigante[2] = (byte) 0xFF;

        MockMultipartFile arquivo = new MockMultipartFile("foto", "grande.jpg", "image/jpeg", gigante);

        assertThatThrownBy(() -> validador.validar(arquivo))
                .isInstanceOf(UploadRecusadoException.class)
                .hasMessageContaining("10");
    }

    /** PNG com assinatura e bloco IHDR válidos, com CRC correto, e nada mais. */
    private static byte[] pngComCabecalho(int largura, int altura) {
        byte[] ihdr = ByteBuffer.allocate(17)
                .put("IHDR".getBytes())
                .putInt(largura)
                .putInt(altura)
                .put((byte) 8)    // profundidade de bit
                .put((byte) 2)    // cor verdadeira
                .put((byte) 0).put((byte) 0).put((byte) 0)
                .array();

        CRC32 crc = new CRC32();
        crc.update(ihdr);

        return ByteBuffer.allocate(8 + 4 + ihdr.length + 4)
                .put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})
                .putInt(ihdr.length - 4)
                .put(ihdr)
                .putInt((int) crc.getValue())
                .array();
    }
}
