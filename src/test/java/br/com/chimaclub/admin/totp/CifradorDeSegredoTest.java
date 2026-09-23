package br.com.chimaclub.admin.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CifradorDeSegredoTest {

    private static final String SEGREDO = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    /** 32 bytes, como a chave de AES-256 exige. */
    private static final String CHAVE =
            Base64.getEncoder().encodeToString("chave-de-teste-com-32-bytes-1234".getBytes());

    private final CifradorDeSegredo cifrador = new CifradorDeSegredo(CHAVE);

    @Test
    @DisplayName("o texto cifrado não contém o segredo em claro")
    void naoVazaOSegredo() {
        String cifrado = cifrador.cifrar(SEGREDO);

        assertThat(cifrado).doesNotContain(SEGREDO);
        assertThat(cifrador.decifrar(cifrado)).isEqualTo(SEGREDO);
    }

    @Test
    @DisplayName("cifrar duas vezes o mesmo segredo dá resultados diferentes")
    void usaNonceNovoACadaVez() {
        assertThat(cifrador.cifrar(SEGREDO))
                .as("nonce repetido em AES-GCM quebra a cifra por completo")
                .isNotEqualTo(cifrador.cifrar(SEGREDO));
    }

    @Test
    @DisplayName("um texto cifrado adulterado é recusado, não decifrado errado")
    void detectaAdulteracao() {
        byte[] bytes = Base64.getDecoder().decode(cifrador.cifrar(SEGREDO));
        bytes[bytes.length - 1] ^= 0x01;
        String adulterado = Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> cifrador.decifrar(adulterado))
                .as("é para isto que serve o GCM: autenticação, não só sigilo")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("um texto cifrado com outra chave é recusado")
    void recusaTextoDeOutraChave() {
        String outraChave = Base64.getEncoder().encodeToString("outra-chave-com-32-bytes-1234567".getBytes());
        String cifradoComOutra = new CifradorDeSegredo(outraChave).cifrar(SEGREDO);

        assertThatThrownBy(() -> cifrador.decifrar(cifradoComOutra))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("uma chave de outro tamanho é recusada na construção, não no uso")
    void recusaChaveDeTamanhoErrado() {
        assertThatThrownBy(() -> new CifradorDeSegredo(Base64.getEncoder().encodeToString("curta".getBytes())))
                .as("falhar na partida é melhor que falhar no primeiro login")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("uma chave ausente é recusada com mensagem que explica o que fazer")
    void recusaChaveAusente() {
        assertThatThrownBy(() -> new CifradorDeSegredo(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.chave-totp");
    }
}
