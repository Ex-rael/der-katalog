package br.com.chimaclub.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "evento_auditoria")
public class EventoAuditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ON DELETE SET NULL no banco: apagar um administrador não pode apagar
     * a trilha do que ele fez.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private UsuarioAdmin usuario;

    @Column(nullable = false, length = 60)
    private String acao;

    @Column(length = 60)
    private String entidade;

    @Column(name = "entidade_id")
    private UUID entidadeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detalhes;

    /**
     * A coluna é INET no PostgreSQL, que o Hibernate não mapeia sozinho.
     * SqlTypes.INET faz a ponte; o serviço valida o endereço antes, porque
     * um valor inválido faria o banco recusar a linha inteira e a trilha de
     * auditoria se perderia justamente no evento suspeito.
     */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(columnDefinition = "inet")
    private String ip;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm = Instant.now();

    protected EventoAuditoria() {
        // exigido pelo JPA
    }

    EventoAuditoria(String acao, UsuarioAdmin usuario, String entidade, UUID entidadeId,
                    Map<String, Object> detalhes, String ip, String userAgent) {
        this.acao = acao;
        this.usuario = usuario;
        this.entidade = entidade;
        this.entidadeId = entidadeId;
        this.detalhes = detalhes;
        this.ip = ip;
        this.userAgent = userAgent;
    }

    public Long getId() { return id; }
    public UsuarioAdmin getUsuario() { return usuario; }
    public String getAcao() { return acao; }
    public String getEntidade() { return entidade; }
    public UUID getEntidadeId() { return entidadeId; }
    public Map<String, Object> getDetalhes() { return detalhes; }
    public String getIp() { return ip; }
    public String getUserAgent() { return userAgent; }
    public Instant getCriadoEm() { return criadoEm; }
}
