package br.com.chimaclub.config_loja;

import br.com.chimaclub.admin.Acao;
import br.com.chimaclub.admin.AuditoriaService;
import br.com.chimaclub.admin.UsuarioAdmin;
import br.com.chimaclub.comum.RegraDeNegocioException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configuração da loja: número de WhatsApp, texto da mensagem, Instagram.
 *
 * O número de WhatsApp é o ativo de sensibilidade média da §1.3. Trocá-lo é
 * o caminho mais curto de um golpe contra os clientes: o catálogo continua
 * parecendo legítimo, e a conversa vai para outra pessoa. Daí duas decisões
 * aqui: o formato é validado em vez de aceito como veio, e toda alteração
 * vai para a auditoria com o valor anterior e o novo.
 */
@Service
public class ConfiguracaoService {

    public static final String WHATSAPP_NUMERO = "whatsapp_numero";
    public static final String WHATSAPP_EXIBIDO = "whatsapp_exibido";
    public static final String WHATSAPP_MENSAGEM = "whatsapp_mensagem";
    public static final String INSTAGRAM = "instagram";
    public static final String LOJA_NOME = "loja_nome";
    public static final String LOJA_LEMA = "loja_lema";

    /** Código do país mais número: entre 12 e 15 dígitos, sem nada mais. */
    private static final Pattern NUMERO_VALIDO = Pattern.compile("^\\d{12,15}$");

    /** Perfil do Instagram: letras, dígitos, ponto e sublinhado. */
    private static final Pattern INSTAGRAM_VALIDO = Pattern.compile("^[A-Za-z0-9._]{1,30}$");

    private static final int LIMITE_DE_TEXTO = 300;

    private final ConfiguracaoRepository repositorio;
    private final AuditoriaService auditoria;

    public ConfiguracaoService(ConfiguracaoRepository repositorio, AuditoriaService auditoria) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public Map<String, String> todas() {
        Map<String, String> mapa = new LinkedHashMap<>();
        repositorio.findAll().forEach(item -> mapa.put(item.getChave(), item.getValor()));
        return mapa;
    }

    @Transactional(readOnly = true)
    public String valor(String chave) {
        return repositorio.findById(chave).map(Configuracao::getValor).orElse("");
    }

    @Transactional
    public void gravar(Map<String, String> novos, UsuarioAdmin autor, HttpServletRequest requisicao) {
        validar(novos);

        Map<String, Object> mudancas = new LinkedHashMap<>();
        novos.forEach((chave, valorNovo) -> {
            String valorAnterior = valor(chave);
            if (!valorAnterior.equals(valorNovo)) {
                mudancas.put(chave + "_anterior", valorAnterior);
                mudancas.put(chave + "_novo", valorNovo);
                repositorio.save(new Configuracao(chave, valorNovo));
            }
        });

        if (!mudancas.isEmpty()) {
            auditoria.registrar(Acao.CONFIGURACAO_ALTERADA, autor, "configuracao", null,
                    mudancas, requisicao);
        }
    }

    private void validar(Map<String, String> novos) {
        String numero = novos.get(WHATSAPP_NUMERO);
        if (numero != null && !NUMERO_VALIDO.matcher(numero.trim()).matches()) {
            throw new RegraDeNegocioException(
                    "O número de WhatsApp precisa ter só dígitos, com código do país, "
                    + "como em 5551989250481.");
        }

        String instagram = novos.get(INSTAGRAM);
        if (instagram != null && !instagram.isBlank()
                && !INSTAGRAM_VALIDO.matcher(instagram.trim()).matches()) {
            throw new RegraDeNegocioException(
                    "O perfil do Instagram aceita apenas letras, números, ponto e sublinhado.");
        }

        String mensagem = novos.get(WHATSAPP_MENSAGEM);
        if (mensagem != null && mensagem.length() > LIMITE_DE_TEXTO) {
            throw new RegraDeNegocioException(
                    "A mensagem padrão pode ter no máximo " + LIMITE_DE_TEXTO + " caracteres.");
        }

        for (Map.Entry<String, String> item : novos.entrySet()) {
            if (item.getValue() != null && item.getValue().length() > LIMITE_DE_TEXTO) {
                throw new RegraDeNegocioException(
                        "O campo " + item.getKey() + " passou do limite de "
                        + LIMITE_DE_TEXTO + " caracteres.");
            }
            // Cabeçalho de resposta nunca recebe valor do usuário sem
            // limpeza (§A03); aqui nem chega a haver o caso, mas quebra de
            // linha em campo de uma linha só é sinal de tentativa.
            if (item.getValue() != null && (item.getValue().contains("\r") || item.getValue().contains("\n"))) {
                throw new RegraDeNegocioException(
                        "O campo " + item.getKey() + " não aceita quebra de linha.");
            }
        }
    }
}
