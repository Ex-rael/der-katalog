package br.com.chimaclub.admin;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Criação do administrador por linha de comando, na própria máquina:
 *
 *   docker compose run --rm --no-deps app \
 *     java -jar /aplicacao/aplicacao.jar --criar-admin=dona@exemplo.com --nome=Dona
 *
 * Não existe cadastro público nem recuperação de senha por e-mail. As duas
 * ausências são deliberadas: cada uma seria uma superfície de ataque
 * alcançável sem credencial, e nenhuma das duas é necessária num sistema de
 * um usuário só, com acesso físico à máquina.
 *
 * A senha é sorteada aqui e impressa uma única vez em System.out — nunca no
 * log da aplicação, que é rotacionado, copiado em backup e lido depois.
 *
 * A aplicação encerra ao fim do comando. Sem isso, pedir a criação de um
 * administrador subiria o servidor web inteiro e o deixaria no ar: quem
 * rodasse o comando com a loja em produção veria as portas em conflito, e
 * quem rodasse sem ela ficaria esperando um prompt que nunca volta.
 */
@Component
public class CriarAdminCommand implements ApplicationRunner {

    private static final int BYTES_DE_SENHA = 24;

    private final UsuarioAdminRepository usuarios;
    private final PasswordEncoder codificador;
    private final ApplicationContext contexto;

    public CriarAdminCommand(UsuarioAdminRepository usuarios, PasswordEncoder codificador,
                             ApplicationContext contexto) {
        this.usuarios = usuarios;
        this.codificador = codificador;
        this.contexto = contexto;
    }

    /**
     * O método do runner NÃO é transacional, e o encerramento acontece aqui,
     * depois que criar() retornou.
     *
     * A primeira versão chamava System.exit de dentro de um método anotado
     * com @Transactional, e o efeito foi o pior possível: a transação nunca
     * chegava ao commit, o comando imprimia a senha com ar de sucesso, e o
     * administrador não existia no banco. Um erro que só aparece quando
     * alguém tenta entrar.
     */
    @Override
    public void run(ApplicationArguments argumentos) {
        List<String> emails = argumentos.getOptionValues("criar-admin");
        if (emails == null || emails.isEmpty()) {
            return;
        }

        String email = emails.getFirst().trim();
        List<String> nomes = argumentos.getOptionValues("nome");
        String nome = (nomes == null || nomes.isEmpty()) ? "Administradora" : nomes.getFirst();

        Optional<String> senha = criar(email, nome);

        if (senha.isEmpty()) {
            System.out.println("\nJá existe administrador com o e-mail " + email + ".\n");
            encerrar(1);
            return;
        }

        System.out.println("""

                ┌──────────────────────────────────────────────────────────────┐
                │ Administrador criado                                         │
                └──────────────────────────────────────────────────────────────┘

                  e-mail: %s
                   senha: %s

                Guarde a senha no gerenciador de senhas agora: ela não é
                gravada em lugar nenhum e não há como recuperá-la depois.

                Em produção, ative o segundo fator no primeiro acesso.
                """.formatted(email, senha.get()));

        encerrar(0);
    }

    /**
     * Cria o administrador e devolve a senha sorteada, ou vazio se o e-mail
     * já existir. Separado do runner para poder ser testado: o runner
     * encerra o processo, e um teste que o chamasse derrubaria a suíte.
     */
    @Transactional
    public Optional<String> criar(String email, String nome) {
        if (usuarios.existsByEmailIgnoreCase(email)) {
            return Optional.empty();
        }

        String senha = senhaAleatoria();
        usuarios.save(new UsuarioAdmin(email, nome, codificador.encode(senha)));
        return Optional.of(senha);
    }

    private void encerrar(int codigo) {
        System.out.flush();
        System.exit(SpringApplication.exit(contexto, () -> codigo));
    }

    private static String senhaAleatoria() {
        byte[] bytes = new byte[BYTES_DE_SENHA];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
