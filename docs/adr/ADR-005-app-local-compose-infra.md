# ADR-005: Aplicação roda local; Compose contém apenas infraestrutura

## Contexto

O `compose.yml` original incluía o serviço `app` (build via `Dockerfile`), subindo aplicação + PostgreSQL + WireMock de uma vez. Durante a fase de desenvolvimento isso atrapalha:

- A cada alteração no código é preciso `docker compose up --build` (rebuild da imagem Java).
- O container mapeia `8080:8080`, conflitando com a porta da aplicação rodando localmente.
- Iterar código → testar → depurar fica lento comparado a `./mvnw spring-boot:run`.

## Decisão

Remover o serviço `app` do `compose.yml`. O compose passa a subir **apenas infraestrutura**:

| Serviço        | Imagem                       | Porta  | Função                          |
|----------------|------------------------------|--------|---------------------------------|
| `postgres`     | `postgres:16`                | `5432` | Banco de dados                  |
| `provider-mock`| `wiremock/wiremock:3.12.1`   | `9090` | Stub do provedor externo        |

A aplicação roda localmente via `./mvnw spring-boot:run`, apontando para esses containers por variáveis de ambiente.

O `Dockerfile` multi-stage permanece no repositório (é obrigatório do desafio, item 11) e será usado em produção/CI. O serviço `app` pode ser reintroduzido no compose via profile quando necessário (ex.: smoke test da imagem).

## Consequências

- **Dev local**: `docker compose up postgres provider-mock` + `./mvnw spring-boot:run`. Ciclo de hot-reload rápido (Spring DevTools já está no classpath).
- **Porta 8080 livre**: a app local ocupa 8080 sem conflito com container.
- **Testes de integração**: continuam usando Testcontainers ( PostgreSQL + stub em memória ), sem depender do compose.
- **Produção**: `docker build -t product-activation .` + execução da imagem — o Dockerfile não é afetado por esta decisão.
- **Reintrodução futura**: adicionar `profiles: ["full"]` no serviço `app` permite `docker compose --profile full up` para o ambiente completo quando fizer sentido (ex.: validar a imagem antes de subir pipeline).

## Por que não usar profile em vez de remover

Poder-se-ia manter o serviço `app` sob um profile e simplesmente não ativá-lo. A remoção foi escolhida porque:

1. O compose fica mais legível — só o que é usado no dia a dia.
2. Evita que `docker compose up` (sem args) suba tudo por engano.
3. Reintroduzir é trivial (git history + profile).
