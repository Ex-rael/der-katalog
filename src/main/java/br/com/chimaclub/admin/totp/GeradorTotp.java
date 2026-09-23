package br.com.chimaclub.admin.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * TOTP do RFC 6238 sobre HMAC-SHA1, que é o que os aplicativos
 * autenticadores implementam. A correção é provada contra os vetores do
 * apêndice B do próprio RFC, em GeradorTotpTest.
 */
public final class GeradorTotp {

    public static final Duration PASSO = Duration.ofSeconds(30);
    public static final int DIGITOS = 6;

    private static final int BYTES_DO_SEGREDO = 20;   // 160 bits, RFC 4226 §4
    private static final SecureRandom SORTEIO = new SecureRandom();

    private GeradorTotp() {
    }

    public static String novoSegredo() {
        byte[] bytes = new byte[BYTES_DO_SEGREDO];
        SORTEIO.nextBytes(bytes);
        return Base32.codificar(bytes);
    }

    public static String codigo(String segredoBase32, Instant instante, int digitos) {
        long contador = Math.floorDiv(instante.getEpochSecond(), PASSO.toSeconds());
        byte[] chave = Base32.decodificar(segredoBase32);
        byte[] mensagem = ByteBuffer.allocate(Long.BYTES).putLong(contador).array();

        byte[] resumo;
        try {
            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(chave, "HmacSHA1"));
            resumo = hmac.doFinal(mensagem);
        } catch (GeneralSecurityException naoDeveriaAcontecer) {
            throw new IllegalStateException("HMAC-SHA1 indisponível nesta JVM", naoDeveriaAcontecer);
        }

        // Truncamento dinâmico do RFC 4226 §5.3.
        int deslocamento = resumo[resumo.length - 1] & 0x0F;
        int binario = ((resumo[deslocamento] & 0x7F) << 24)
                | ((resumo[deslocamento + 1] & 0xFF) << 16)
                | ((resumo[deslocamento + 2] & 0xFF) << 8)
                | (resumo[deslocamento + 3] & 0xFF);

        int modulo = (int) Math.pow(10, digitos);
        return String.format("%0" + digitos + "d", binario % modulo);
    }

    public static String codigo(String segredoBase32, Instant instante) {
        return codigo(segredoBase32, instante, DIGITOS);
    }
}
