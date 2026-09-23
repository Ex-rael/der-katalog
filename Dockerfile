# =========================================================
# Construção em duas etapas.
#
# O que chega à máquina de produção leva apenas o JRE e o jar: sem
# compilador, sem código-fonte, sem cache do Maven. Menos coisa na imagem é
# menos coisa para um invasor usar depois de entrar — um compilador dentro
# do contêiner transforma uma execução limitada de código em muito mais.
# =========================================================

# --- Etapa 1: construir ---------------------------------
FROM maven:3.9-eclipse-temurin-21 AS construcao

WORKDIR /construcao

# As dependências primeiro, em camada própria: elas mudam muito menos que o
# código, e assim uma alteração no código não rebaixa a internet inteira.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
# Os testes rodam fora daqui, no ciclo de desenvolvimento: aqui eles
# exigiriam um PostgreSQL e o Docker de dentro do Docker.
RUN mvn -q -B clean package -DskipTests

# --- Etapa 2: executar ----------------------------------
FROM eclipse-temurin:21-jre-noble

# Fixar por digest antes de publicar (§A06):
#   docker buildx imagetools inspect eclipse-temurin:21-jre-noble

# Usuário sem privilégio, com identificador fixo, para casar com o "user:"
# do Compose e com a posse do volume de fotos.
RUN groupadd --gid 10001 chimaclub \
 && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin chimaclub

# A nativa do webp-imageio precisa da biblioteca padrão de C++, que a imagem
# do JRE não traz. Sem ela, o processador recua para JPEG em silêncio.
RUN apt-get update \
 && apt-get install --no-install-recommends -y libstdc++6 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /aplicacao
COPY --from=construcao /construcao/target/*.jar aplicacao.jar

# Os diretórios de escrita são volumes; a imagem em si fica somente leitura.
RUN mkdir -p /var/chimaclub/fotos /var/chimaclub/tmp /var/log/chimaclub \
 && chown -R 10001:10001 /var/chimaclub /var/log/chimaclub

USER 10001:10001

EXPOSE 8080 8081

# A JVM respeita os limites do contêiner por padrão desde o Java 10; o
# -XX:MaxRAMPercentage ajusta a fatia do que estiver disponível.
# java.io.tmpdir fora do /tmp: a nativa que grava WebP se extrai para cá e
# precisa mapeá-la como executável, e o /tmp é montado com noexec porque é
# onde os arquivos enviados aterrissam. Separar os dois caminhos preserva a
# proteção e faz a nativa funcionar.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom -Djava.io.tmpdir=/var/chimaclub/tmp"

ENTRYPOINT ["java", "-jar", "/aplicacao/aplicacao.jar"]
