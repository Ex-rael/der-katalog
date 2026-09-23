package br.com.chimaclub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Rotinas agendadas: a limpeza das fotos de produto excluído (§6.6).
@EnableScheduling
public class ChimaClubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChimaClubApplication.class, args);
    }
}
