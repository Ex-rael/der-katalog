package br.com.chimaclub.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Quem mudou o quê e quando (§A09). Grava no banco, para consulta pela tela
 * de auditoria, e em arquivo de log, para o caso de o banco ser justamente o
 * que foi comprometido.
 */
@Service
public class AuditoriaService {

    private static final Logger LOG = LoggerFactory.getLogger("auditoria");
    private static final int LIMITE_USER_AGENT = 300;

    /**
     * Rede de proteção, não a proteção principal. O certo é não passar
     * esses dados; esta lista garante que um descuido futuro em alguma
     * chamada não vire um vazamento permanente gravado no banco e copiado
     * para todos os backups.
     */
    private static final List<String> FRAGMENTOS_SENSIVEIS =
            List.of("senha", "password", "segredo", "secret", "token", "cookie", "hash", "authorization");

    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)$");

    /** Forma literal, com ou sem abreviação por "::", e sem zona de escopo. */
    private static final Pattern IPV6 = Pattern.compile(
            "^(?=.*:)(?!.*::.*::)(?!.*:::)[0-9A-Fa-f:]{2,45}$");

    private final EventoAuditoriaRepository eventos;

    public AuditoriaService(EventoAuditoriaRepository eventos) {
        this.eventos = eventos;
    }

    /**
     * REQUIRES_NEW de propósito: o registro de auditoria não pode ser
     * desfeito pelo rollback da operação que o originou. Uma tentativa de
     * alteração que falhou é exatamente o que mais interessa na revisão
     * mensal.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(Acao acao, UsuarioAdmin usuario, String entidade, UUID entidadeId,
                          Map<String, Object> detalhes, HttpServletRequest requisicao) {

        String ip = enderecoValido(requisicao == null ? null : requisicao.getRemoteAddr());
        String userAgent = truncar(requisicao == null ? null : requisicao.getHeader("User-Agent"));

        eventos.save(new EventoAuditoria(
                acao.name(), usuario, entidade, entidadeId, semCamposSensiveis(detalhes), ip, userAgent));

        LOG.info("acao={} usuario={} entidade={} entidadeId={} ip={}",
                acao, usuario == null ? "-" : usuario.getEmail(), entidade, entidadeId, ip);
    }

    /** Atalho para eventos sem entidade associada, como os de login. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(Acao acao, UsuarioAdmin usuario, Map<String, Object> detalhes,
                          HttpServletRequest requisicao) {
        registrar(acao, usuario, null, null, detalhes, requisicao);
    }

    private static Map<String, Object> semCamposSensiveis(Map<String, Object> detalhes) {
        if (detalhes == null || detalhes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> limpo = new LinkedHashMap<>();
        detalhes.forEach((chave, valor) -> {
            String minuscula = chave.toLowerCase();
            boolean sensivel = FRAGMENTOS_SENSIVEIS.stream().anyMatch(minuscula::contains);
            if (!sensivel) {
                limpo.put(chave, valor);
            }
        });
        return limpo;
    }

    /**
     * A coluna é INET, e um valor inválido faria o banco recusar a linha
     * inteira — perdendo o evento junto com o endereço. Endereço que não
     * seja IP vira nulo, e o evento é gravado assim mesmo.
     *
     * A conferência é puramente sintática, de propósito. InetAddress.getByName
     * resolveria por DNS o que não fosse literal, e isso seria uma requisição
     * de saída disparada por entrada do usuário — exatamente o que o §A10
     * elimina na origem. InetAddress.ofLiteral resolveria isso, mas só existe
     * do Java 22 em diante, e o projeto está no 21.
     */
    private static String enderecoValido(String endereco) {
        if (endereco == null || endereco.isBlank()) {
            return null;
        }
        String limpo = endereco.trim();
        if (IPV4.matcher(limpo).matches() || IPV6.matcher(limpo).matches()) {
            return limpo;
        }
        LOG.warn("endereço de origem em formato inesperado; evento gravado sem IP");
        return null;
    }

    private static String truncar(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= LIMITE_USER_AGENT
                ? userAgent
                : userAgent.substring(0, LIMITE_USER_AGENT);
    }
}
