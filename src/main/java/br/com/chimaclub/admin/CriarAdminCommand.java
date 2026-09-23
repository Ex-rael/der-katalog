package br.com.chimaclub.admin;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Criação do administrador por linha de comando, na própria máquina:
 *
 *   ./mvnw spring-boot:run \
 *     -Dspring-boot.run.arguments="--criar-admin=dona@exemplo.com --nome=Dona"
 *
 * Não existe cadastro público nem recuperação de senha por e-mail. As duas
 * ausências são deliberadas: cada uma seria uma superfície de ataque
 * alcançável sem credencial, e nenhuma das duas é necessária num sistema de
 * um usuário só, com acesso físico à máquina.
 *
 * A senha é sorteada aqui e impressa uma única vez em System.out — nunca no
 * log da aplicação, que é rotacionado, copiado em backup e lido depois.
 */
@Component
public class CriarAdminCommand implements ApplicationRunner {

    private static final int BYTES_DE_SENHA = 24;

    private final UsuarioAdminRepository usuarios;
    private final PasswordEncoder codificador;

    public CriarAdminCommand(UsuarioAdminRepository usuarios, PasswordEncoder codificador) {
        this.usuarios = usuarios;
        this.codificador = codificador;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments argumentos) {
        List<String> emails = argumentos.getOptionValues("criar-admin");
        if (emails == null || emails.isEmpty()) {
            return;
        }

        String email = emails.getFirst().trim();
        List<String> nomes = argumentos.getOptionValues("nome");
        String nome = (nomes == null || nomes.isEmpty()) ? "Administradora" : nomes.getFirst();

        if (usuarios.existsByEmailIgnoreCase(email)) {
            System.out.println("\nJá existe administrador com o e-mail " + email
                    + ". Para trocar a senha, use --trocar-senha.\n");
            return;
        }

        String senha = senhaAleatoria();
        usuarios.save(new UsuarioAdmin(email, nome, codificador.encode(senha)));

        System.out.println("""

                ┌──────────────────────────────────────────────────────────────┐
                │ Administrador criado                                         │
                └──────────────────────────────────────────────────────────────┘

                  e-mail: %s
                   senha: %s

                Guarde a senha no gerenciador de senhas agora: ela não é
                gravada em lugar nenhum e não há como recuperá-la depois.

                Em produção, ative o segundo fator no primeiro acesso.
                """.formatted(email, senha));
    }

    private static String senhaAleatoria() {
        byte[] bytes = new byte[BYTES_DE_SENHA];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
