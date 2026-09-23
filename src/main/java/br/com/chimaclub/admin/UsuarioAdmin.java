package br.com.chimaclub.admin;

import br.com.chimaclub.comum.GeradorDeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * O único papel do sistema na versão 1 é ADMIN, e espera-se um único
 * usuário. As regras de bloqueio moram aqui, e não num serviço, porque são
 * invariantes do próprio usuário: não existe estado válido em que o
 * contador passe de cinco sem bloqueio.
 */
@Entity
@Table(name = "usuario_admin")
public class UsuarioAdmin {

    /** Cinco falhas bloqueiam; o bloqueio dura quinze minutos (§A04). */
    public static final int FALHAS_ATE_BLOQUEAR = 5;
    public static final Duration DURACAO_DO_BLOQUEIO = Duration.ofMinutes(15);

    @Id
    private UUID id = GeradorDeId.novo();

    @Column(nullable = false, length = 160, unique = true)
    private String email;

    @Column(nullable = false, length = 120)
    private String nome;

    /** Hash BCrypt com prefixo de algoritmo. Jamais a senha em claro. */
    @Column(name = "senha_hash", nullable = false, length = 100)
    private String senhaHash;

    @Column(nullable = false)
    private boolean ativo = true;

    /** Cifrado com AES-GCM antes de chegar aqui. */
    @Column(name = "totp_segredo", length = 120)
    private String totpSegredo;

    @Column(name = "totp_ativo", nullable = false)
    private boolean totpAtivo;

    @Column(name = "falhas_login", nullable = false)
    private int falhasLogin;

    @Column(name = "bloqueado_ate")
    private Instant bloqueadoAte;

    @Column(name = "senha_alterada_em", nullable = false)
    private Instant senhaAlteradaEm = Instant.now();

    @Column(name = "ultimo_login_em")
    private Instant ultimoLoginEm;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm = Instant.now();

    protected UsuarioAdmin() {
        // exigido pelo JPA
    }

    public UsuarioAdmin(String email, String nome, String senhaHash) {
        this.email = email;
        this.nome = nome;
        this.senhaHash = senhaHash;
    }

    public void registrarFalha() {
        this.falhasLogin++;
        if (this.falhasLogin >= FALHAS_ATE_BLOQUEAR) {
            this.bloqueadoAte = Instant.now().plus(DURACAO_DO_BLOQUEIO);
        }
    }

    public void registrarSucesso() {
        this.falhasLogin = 0;
        this.bloqueadoAte = null;
        this.ultimoLoginEm = Instant.now();
    }

    public boolean estaBloqueado() {
        return bloqueadoAte != null && bloqueadoAte.isAfter(Instant.now());
    }

    /** Usado pelo teste de expiração e pela liberação manual do bloqueio. */
    public void bloquearAte(Instant instante) {
        this.bloqueadoAte = instante;
    }

    public void trocarSenha(String novoHash) {
        this.senhaHash = novoHash;
        this.senhaAlteradaEm = Instant.now();
        registrarSucesso();
    }

    public void desativar() {
        this.ativo = false;
    }

    public void definirTotp(String segredoCifrado) {
        this.totpSegredo = segredoCifrado;
        this.totpAtivo = true;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getNome() { return nome; }
    public String getSenhaHash() { return senhaHash; }
    public boolean isAtivo() { return ativo; }
    public String getTotpSegredo() { return totpSegredo; }
    public boolean isTotpAtivo() { return totpAtivo; }
    public int getFalhasLogin() { return falhasLogin; }
    public Instant getBloqueadoAte() { return bloqueadoAte; }
    public Instant getSenhaAlteradaEm() { return senhaAlteradaEm; }
    public Instant getUltimoLoginEm() { return ultimoLoginEm; }
    public Instant getCriadoEm() { return criadoEm; }

    /**
     * Sem senha, sem hash e sem segredo TOTP: esta classe acaba em log de
     * depuração e em mensagem de erro, e não pode levar nada disso junto.
     */
    @Override
    public String toString() {
        return "UsuarioAdmin[id=" + id + ", email=" + email + ", ativo=" + ativo + "]";
    }
}
