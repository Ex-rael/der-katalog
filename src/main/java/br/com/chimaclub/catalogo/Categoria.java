package br.com.chimaclub.catalogo;

import br.com.chimaclub.comum.GeradorDeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "categoria")
public class Categoria {

    @Id
    private UUID id = GeradorDeId.novo();

    @Column(nullable = false, length = 80)
    private String nome;

    @Column(nullable = false, length = 80, unique = true)
    private String slug;

    @Column(nullable = false)
    private int ordem;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm = Instant.now();

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm = Instant.now();

    protected Categoria() {
        // exigido pelo JPA
    }

    public Categoria(String nome, String slug, int ordem) {
        this.nome = nome;
        this.slug = slug;
        this.ordem = ordem;
    }

    @PreUpdate
    void aoAtualizar() {
        this.atualizadoEm = Instant.now();
    }

    public UUID getId() { return id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public int getOrdem() { return ordem; }
    public void setOrdem(int ordem) { this.ordem = ordem; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
}
