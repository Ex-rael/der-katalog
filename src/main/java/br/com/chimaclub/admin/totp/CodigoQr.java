package br.com.chimaclub.admin.totp;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Desenha o código QR da ativação do segundo fator.
 *
 * Existe porque digitar à mão um segredo de trinta e dois caracteres em
 * base32 é o tipo de tarefa que faz alguém desistir do segundo fator. Um
 * controle de segurança que não é ativado não protege nada, e a usabilidade
 * aqui é parte da segurança, não um enfeite.
 */
public final class CodigoQr {

    private static final int LADO = 320;
    private static final int MARGEM = 2;

    private CodigoQr() {
    }

    public static byte[] png(String conteudo) {
        try {
            BitMatrix matriz = new QRCodeWriter().encode(conteudo, BarcodeFormat.QR_CODE, LADO, LADO,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                           EncodeHintType.MARGIN, MARGEM,
                           EncodeHintType.CHARACTER_SET, "UTF-8"));

            BufferedImage imagem = new BufferedImage(LADO, LADO, BufferedImage.TYPE_INT_RGB);
            int escuro = new Color(0x0D, 0x33, 0x16).getRGB();   // verde da marca
            int claro = new Color(0xFF, 0xFF, 0xFF).getRGB();

            for (int x = 0; x < LADO; x++) {
                for (int y = 0; y < LADO; y++) {
                    imagem.setRGB(x, y, matriz.get(x, y) ? escuro : claro);
                }
            }

            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            ImageIO.write(imagem, "png", saida);
            return saida.toByteArray();

        } catch (WriterException | IOException falha) {
            throw new IllegalStateException("não foi possível desenhar o código QR", falha);
        }
    }
}
