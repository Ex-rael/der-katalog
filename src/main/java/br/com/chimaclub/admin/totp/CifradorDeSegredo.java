package br.com.chimaclub.admin.totp;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cifra o segredo TOTP em repouso, com AES-GCM (§A02).
 *
 * O segredo em claro no banco vale tanto quanto a senha: quem o obtém gera
 * códigos válidos para sempre, e o segundo fator deixa de ser um segundo
 * fator. Um vazamento do banco — por backup mal guardado, por consulta
 * indevida, por furto do equipamento — não pode entregá-lo.
 *
 * GCM e não CBC porque o modo autentica além de cifrar: um texto adulterado
 * é recusado em vez de decifrado em lixo, o que transformaria um ataque de
 * adulteração em falha silenciosa.
 */
public class CifradorDeSegredo {

    private static final String TRANSFORMACAO = "AES/GCM/NoPadding";
    private static final int BYTES_DA_CHAVE = 32;    // AES-256
    private static final int BYTES_DO_NONCE = 12;    // recomendado para GCM
    private static final int BITS_DA_ETIQUETA = 128;

    private static final SecureRandom SORTEIO = new SecureRandom();

    private final SecretKeySpec chave;

    public CifradorDeSegredo(String chaveEmBase64) {
        if (chaveEmBase64 == null || chaveEmBase64.isBlank()) {
            throw new IllegalArgumentException(
                    "a propriedade app.chave-totp não está definida; em produção ela vem de "
                    + "variável de ambiente, e sem ela o segundo fator não pode ser guardado");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(chaveEmBase64.trim());
        } catch (IllegalArgumentException naoEhBase64) {
            throw new IllegalArgumentException("app.chave-totp precisa estar em base64", naoEhBase64);
        }

        if (bytes.length != BYTES_DA_CHAVE) {
            // Falhar na partida é melhor que falhar no primeiro login: o erro
            // aparece na implantação, e não na hora em que alguém precisa entrar.
            throw new IllegalArgumentException(
                    "app.chave-totp precisa ter exatamente " + BYTES_DA_CHAVE
                    + " bytes depois de decodificada; veio com " + bytes.length);
        }
        this.chave = new SecretKeySpec(bytes, "AES");
    }

    /** Devolve, em base64, o nonce seguido do texto cifrado com a etiqueta. */
    public String cifrar(String textoEmClaro) {
        byte[] nonce = new byte[BYTES_DO_NONCE];
        SORTEIO.nextBytes(nonce);

        try {
            Cipher cifra = Cipher.getInstance(TRANSFORMACAO);
            cifra.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(BITS_DA_ETIQUETA, nonce));
            byte[] cifrado = cifra.doFinal(textoEmClaro.getBytes());

            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + cifrado.length)
                              .put(nonce).put(cifrado).array());
        } catch (GeneralSecurityException falha) {
            throw new IllegalStateException("falha ao cifrar o segredo", falha);
        }
    }

    public String decifrar(String textoCifrado) {
        byte[] tudo;
        try {
            tudo = Base64.getDecoder().decode(textoCifrado);
        } catch (IllegalArgumentException naoEhBase64) {
            throw new IllegalStateException("segredo guardado em formato inesperado", naoEhBase64);
        }
        if (tudo.length <= BYTES_DO_NONCE) {
            throw new IllegalStateException("segredo guardado é curto demais para ser válido");
        }

        byte[] nonce = Arrays.copyOfRange(tudo, 0, BYTES_DO_NONCE);
        byte[] cifrado = Arrays.copyOfRange(tudo, BYTES_DO_NONCE, tudo.length);

        try {
            Cipher cifra = Cipher.getInstance(TRANSFORMACAO);
            cifra.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(BITS_DA_ETIQUETA, nonce));
            return new String(cifra.doFinal(cifrado));
        } catch (GeneralSecurityException falha) {
            // Chave errada ou texto adulterado dão no mesmo aqui, de propósito:
            // distinguir os dois casos ajudaria quem estivesse sondando.
            throw new IllegalStateException("segredo não pôde ser decifrado", falha);
        }
    }
}
