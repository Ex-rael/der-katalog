package br.com.chimaclub.admin.totp;

import br.com.chimaclub.admin.UsuarioAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Segundo fator do painel. Guarda o segredo cifrado, confere o código
 * apresentado e impede que o mesmo código sirva duas vezes.
 */
@Service
public class ServicoTotp {

    /**
     * Uma janela para trás, nenhuma para a frente. Aceitar a janela seguinte
     * cobriria relógio adiantado, mas dobraria o intervalo em que um código
     * capturado ainda vale. Trinta segundos de folga para trás bastam para
     * relógio ligeiramente atrasado e para o tempo de digitação.
     */
    private static final int JANELAS_PARA_TRAS = 1;

    private final CifradorDeSegredo cifrador;

    /**
     * Códigos já usados, por usuário. Sem isto, um código visto por cima do
     * ombro ou capturado da rede continua válido pelos segundos restantes da
     * janela, e um segundo fator que aceita repetição não é bem um segundo
     * fator.
     *
     * Em memória: são no máximo dois usuários e uma entrada por janela de
     * trinta segundos, e perder o registro num reinício custa, no pior caso,
     * uma janela de reuso.
     */
    private final Map<String, String> ultimoCodigoUsado = new ConcurrentHashMap<>();

    public ServicoTotp(@Value("${app.chave-totp:}") String chaveEmBase64) {
        this.cifrador = new CifradorDeSegredo(chaveEmBase64);
    }

    public String gerarSegredo() {
        return GeradorTotp.novoSegredo();
    }

    /** Guarda o segredo já cifrado no usuário e liga o segundo fator. */
    public void ativar(UsuarioAdmin usuario, String segredoEmClaro) {
        usuario.definirTotp(cifrador.cifrar(segredoEmClaro));
    }

    public boolean confere(UsuarioAdmin usuario, String codigoApresentado) {
        if (usuario == null || !usuario.isTotpAtivo() || usuario.getTotpSegredo() == null) {
            return false;
        }

        String segredo = cifrador.decifrar(usuario.getTotpSegredo());
        if (!confereSegredo(segredo, codigoApresentado)) {
            return false;
        }

        String chaveDoUsuario = usuario.getId().toString();
        String jaUsado = ultimoCodigoUsado.get(chaveDoUsuario);
        if (codigoApresentado.equals(jaUsado)) {
            return false;
        }
        ultimoCodigoUsado.put(chaveDoUsuario, codigoApresentado);
        return true;
    }

    public boolean confereSegredo(String segredoEmClaro, String codigoApresentado) {
        if (codigoApresentado == null || !codigoApresentado.matches("\\d{" + GeradorTotp.DIGITOS + "}")) {
            return false;
        }

        Instant agora = Instant.now();
        for (int janela = 0; janela <= JANELAS_PARA_TRAS; janela++) {
            Instant instante = agora.minus(GeradorTotp.PASSO.multipliedBy(janela));
            if (iguaisEmTempoConstante(GeradorTotp.codigo(segredoEmClaro, instante), codigoApresentado)) {
                return true;
            }
        }
        return false;
    }

    public String codigoAgora(String segredoEmClaro) {
        return GeradorTotp.codigo(segredoEmClaro, Instant.now());
    }

    /**
     * URI que o aplicativo autenticador lê do código QR. O segredo aparece
     * aqui em claro, e é por isso que esta tela só existe atrás do login e
     * só na porta administrativa.
     */
    public String uriDeCadastro(String email, String segredoEmClaro) {
        String emissor = URLEncoder.encode("Chima Club", StandardCharsets.UTF_8);
        String conta = URLEncoder.encode(email, StandardCharsets.UTF_8);
        return "otpauth://totp/%s:%s?secret=%s&issuer=%s&digits=%d&period=%d"
                .formatted(emissor, conta, segredoEmClaro, emissor,
                           GeradorTotp.DIGITOS, GeradorTotp.PASSO.toSeconds());
    }

    /**
     * Comparação sem saída antecipada. A diferença de tempo entre "errou no
     * primeiro dígito" e "errou no último" é pequena, mas mensurável em
     * repetição, e com seis dígitos o espaço de busca é pequeno o bastante
     * para que isso importe.
     */
    private static boolean iguaisEmTempoConstante(String esperado, String apresentado) {
        if (esperado.length() != apresentado.length()) {
            return false;
        }
        int diferenca = 0;
        for (int i = 0; i < esperado.length(); i++) {
            diferenca |= esperado.charAt(i) ^ apresentado.charAt(i);
        }
        return diferenca == 0;
    }
}
