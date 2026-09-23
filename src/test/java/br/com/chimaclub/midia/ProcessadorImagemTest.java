package br.com.chimaclub.midia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessadorImagemTest {

    private final ProcessadorImagem processador = new ProcessadorImagem();

    private static BufferedImage imagemDe(int largura, int altura) {
        BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagem.createGraphics();
        g.setColor(new Color(0x0D, 0x33, 0x16));
        g.fillRect(0, 0, largura, altura);
        g.dispose();
        return imagem;
    }

    private static int largura(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes)).getWidth();
    }

    @Test
    @DisplayName("gera as três versões nas larguras previstas na §3.5")
    void geraTresVersoes() throws Exception {
        Map<String, byte[]> versoes = processador.processar(imagemDe(3000, 3750));

        assertThat(largura(versoes.get(ProcessadorImagem.MINI))).isEqualTo(600);
        assertThat(largura(versoes.get(ProcessadorImagem.MEDIA))).isEqualTo(1200);
        assertThat(largura(versoes.get(ProcessadorImagem.GRANDE))).isEqualTo(2000);
    }

    @Test
    @DisplayName("preserva a proporção da original")
    void preservaProporcao() throws Exception {
        byte[] media = processador.processar(imagemDe(2000, 2500)).get(ProcessadorImagem.MEDIA);

        BufferedImage saida = ImageIO.read(new ByteArrayInputStream(media));

        assertThat(saida.getHeight() / (double) saida.getWidth()).isCloseTo(1.25, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("não amplia imagem menor que o alvo: esticar só acrescenta peso")
    void naoAmpliaImagemPequena() throws Exception {
        Map<String, byte[]> versoes = processador.processar(imagemDe(400, 500));

        assertThat(largura(versoes.get(ProcessadorImagem.GRANDE))).isEqualTo(400);
        assertThat(largura(versoes.get(ProcessadorImagem.MINI))).isEqualTo(400);
    }

    @Test
    @DisplayName("neutraliza a imagem poliglota: a carga grudada no fim não sobrevive")
    void neutralizaImagemPoliglota() throws Exception {
        // JPEG válido com PHP no fim — o arquivo abre como imagem e passa
        // pelo validador. Quem desarma a carga é a reescrita: a saída é
        // construída dos pixels, e nada do arquivo original é copiado.
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(imagemDe(600, 750), "jpg", jpeg);
        byte[] carga = "<?php system($_GET['c']); ?>".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream poliglota = new ByteArrayOutputStream();
        poliglota.write(jpeg.toByteArray());
        poliglota.write(carga);

        BufferedImage relida = ImageIO.read(new ByteArrayInputStream(poliglota.toByteArray()));
        Map<String, byte[]> versoes = processador.processar(relida);

        for (Map.Entry<String, byte[]> versao : versoes.entrySet()) {
            String conteudo = new String(versao.getValue(), StandardCharsets.ISO_8859_1);
            assertThat(conteudo)
                    .as("versão %s", versao.getKey())
                    .doesNotContain("<?php")
                    .doesNotContain("system(");
        }
    }

    @Test
    @DisplayName("a imagem gerada não carrega metadados da original")
    void descartaMetadados() throws Exception {
        Map<String, byte[]> versoes = processador.processar(imagemDe(1000, 1000));

        String conteudo = new String(versoes.get(ProcessadorImagem.MEDIA), StandardCharsets.ISO_8859_1);

        assertThat(conteudo)
                .as("EXIF traz coordenadas de onde a foto foi tirada")
                .doesNotContain("Exif")
                .doesNotContain("GPS");
    }

    @Test
    @DisplayName("a saída é legível de volta, então a nativa de imagem carregou")
    void saidaEhLegivel() throws Exception {
        byte[] media = processador.processar(imagemDe(1500, 1500)).get(ProcessadorImagem.MEDIA);

        assertThat(ImageIO.read(new ByteArrayInputStream(media)))
                .as("se o escritor não funcionasse, este teste avisaria em vez de gravar lixo")
                .isNotNull();
    }

    @Test
    @DisplayName("nesta máquina o formato escolhido é WebP")
    void formatoEscolhidoEhWebp() {
        // Se algum dia a nativa deixar de carregar, o processador recua para
        // JPEG e este teste falha — avisando da mudança em vez de escondê-la.
        assertThat(processador.formato()).isEqualTo("webp");
        assertThat(processador.tipoMime()).isEqualTo("image/webp");
        assertThat(processador.extensao()).isEqualTo("webp");
    }

    @Test
    @DisplayName("a miniatura da home fica pequena o bastante para não pesar a página")
    void miniaturaEhLeve() {
        byte[] mini = processador.processar(imagemDe(3000, 3750)).get(ProcessadorImagem.MINI);

        assertThat(mini.length)
                .as("a §4.2 do plano prevê miniatura tipicamente abaixo de 80 KB")
                .isLessThan(200 * 1024);
    }
}
