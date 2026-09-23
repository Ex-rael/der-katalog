package br.com.chimaclub.catalogo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Contagem de cliques no botão de compra.
 *
 * Guarda produto e horário, e nada mais. Sem IP, sem cookie, sem
 * identificador de sessão, sem User-Agent — nada que permita reconstruir
 * quem visitou o quê. Essa é a decisão que mantém o projeto sem dado pessoal
 * de cliente, e com ela o §1.3 do plano de segurança continua verdadeiro:
 * não há o que vazar, e a LGPD não se aplica. É para preservar.
 *
 * O referer é gravado porque diz de qual página do próprio site o clique
 * veio, e é truncado para não virar depósito de texto arbitrário.
 */
@Entity
@Table(name = "clique_whatsapp")
public class CliqueWhatsapp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "produto_id")
    private Produto produto;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm = Instant.now();

    @Column(length = 300)
    private String referer;

    protected CliqueWhatsapp() {
        // exigido pelo JPA
    }

    public CliqueWhatsapp(Produto produto, String referer) {
        this.produto = produto;
        this.referer = referer;
    }

    public Long getId() { return id; }
    public Produto getProduto() { return produto; }
    public Instant getCriadoEm() { return criadoEm; }
    public String getReferer() { return referer; }
}
