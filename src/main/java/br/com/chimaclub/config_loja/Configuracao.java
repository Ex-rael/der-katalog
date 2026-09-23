package br.com.chimaclub.config_loja;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "configuracao")
public class Configuracao {

    @Id
    @Column(length = 60)
    private String chave;

    @Column(nullable = false, columnDefinition = "text")
    private String valor;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm = Instant.now();

    protected Configuracao() {
        // exigido pelo JPA
    }

    public Configuracao(String chave, String valor) {
        this.chave = chave;
        this.valor = valor;
    }

    @PreUpdate
    void aoAtualizar() {
        this.atualizadoEm = Instant.now();
    }

    public String getChave() { return chave; }
    public String getValor() { return valor; }
    public void setValor(String valor) { this.valor = valor; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
}
