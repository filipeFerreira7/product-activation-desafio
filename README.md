# Product Activation — Desafio Técnico Java Pleno/Sênior

[![CI](https://github.com/filipeFerreira7/product-activation-desafio/actions/workflows/ci.yml/badge.svg)](https://github.com/filipeFerreira7/product-activation-desafio/actions/workflows/ci.yml)

Serviço de ativação de produtos: registro de solicitações de ativação, consulta de status e integração com um provedor externo.

---

## Sumário

- [Visão geral da arquitetura](#visão-geral-da-arquitetura)
- [Fluxo de uma ativação](#fluxo-de-uma-ativação)
- [Como executar](#como-executar)
- [Endpoints](#endpoints)
- [Decisões técnicas](#decisões-técnicas)
- [Cenário de análise — produção](#cenário-de-análise--produção)
- [Produção](#produção)
- [Microsserviços e cloud](#microsserviços-e-cloud)
- [Pontos pendentes](#pontos-pendentes)
- [Melhorias futuras](#melhorias-futuras)
- [Uso de IA](#uso-de-ia)

---

## Visão geral da arquitetura

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          SISTEMA EXTERNO                                │
│                     (consumidor da API REST)                            │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │  POST /api/v1/requests
                               │  GET  /api/v1/requests/{id}
                               │  GET  /api/v1/requests?status=&customerId=
                               ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     APPLICATION LAYER (Spring Boot)                     │
│                                                                         │
│  ┌──────────────┐   ┌──────────────────┐   ┌─────────────────────────┐  │
│  │  Security    │──▶│   Controller     │──▶│      Service            │  │
│  │  Filter      │   │  (REST endpoints)│   │  (business rules)       │  │
│  │  Chain       │   └──────────────────┘   └────────┬────────────────┘  │
│  │  (OAuth2 RS) │                                   │                   │
│  └──────────────┘                                   │                   │
│                                                     ▼                   │
│                                          ┌──────────────────────┐       │
│                                          │  Repository (JPA)    │       │
│                                          └──────────┬───────────┘       │
│                                                     │                   │
│  ┌──────────────────────────────────────────────────┼───────────────┐   │
│  │              INTEGRATION LAYER                   │               │   │
│  │                                                  ▼               │   │
│  │  ┌────────────────┐    ┌──────────────┐   ┌───────────────┐      │   │
│  │  │  Dispatcher    │───▶│  Processor   │──▶│  Provider     │      │   │
│  │  │  (retry loop)  │    │  (state      │   │  Client       │      │   │
│  │  │                │    │   transitions│   │  (RestClient) │      │   │
│  │  └────────────────┘    │   + HTTP)    │   └───────┬───────┘      │   │
│  │                        └──────────────┘           │              │   │
│  └───────────────────────────────────────────────────┼──────────────┘   │
│                                                      │                  │
│  ┌───────────────────────────────────────────────────┼──────────────┐   │
│  │  Cross-cutting: CorrelationIdFilter, AsyncConfig, │Transaction   │   │
│  │  SecurityConfig (conditional), ProblemDetails     │Config        │   │
│  └───────────────────────────────────────────────────┴──────────────┘   │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    ▼                           ▼
        ┌───────────────────┐       ┌──────────────────────┐
        │   PostgreSQL 16   │       │   WireMock (stub)    │
        │   (Flyway + JPA)  │       │  POST /provider/v1/  │
        │                   │       │      activate        │
        └───────────────────┘       └──────────────────────┘
```

### Camadas e responsabilidades

| Camada | Componentes | Responsabilidade |
|--------|------------|------------------|
| **API** | `ActivationController`, DTOs (`record`) | Expor endpoints REST, validação de entrada, mapeamento DTO ↔ entidade |
| **Domínio** | `Activation`, `ActivationStatus` | Regras de vida: transições controladas (`markProcessing`, `markCompleted`, `markFailed`, `backToPending`), lock otimista (`@Version`) |
| **Serviço** | `ActivationServiceImpl` | Validação defensiva de campos, orquestração de criação (anti-duplicidade + retry idempotente), consulta, listagem paginada |
| **Persistência** | `ActivationRepository`, Flyway `V1__create_activations_table.sql` | Acesso a dados JPA, migração versionada, constraint `UNIQUE` |
| **Integração** | `ActivationDispatcher`, `ActivationProcessor`, `RestClientActivationProviderClient` | Chamada assíncrona pós-commit ao provedor, retry com backoff, transição de status por retorno HTTP |
| **Transversal** | `GlobalExceptionHandler`, `CorrelationIdFilter`, `SecurityConfig`, `AsyncConfig`, `TransactionConfig`, `ResilienceConfig` | Problem Details (RFC 9457), correlação de erro (`X-Trace-Id`), OAuth2 Resource Server condicional, thread pool, template de transação, circuit breaker do provedor, rate limit da API |

### Decisões de empacotamento

| Decisão | Justificativa | ADR |
|---------|--------------|-----|
| Camadas simples `controller → service → repository` | O desafio avalia simplicidade; over-engineering é penalizado | — |
| Domínio rico (transições nos métodos da entidade, não setters livres) | Invariante de estado protegida no domínio, não no service | — |
| DTOs `record` separados da entidade JPA | Evita vazar schema interno; validação nos DTOs | — |
| Assíncrono pós-commit para chamada remota | Evita segurar conexão de banco durante HTTP lento | [ADR-002](docs/adr/ADR-002-processamento-assincrono-pos-commit.md) |
| Flyway como fonte de verdade do schema | Migrações imutáveis, Hibernate só valida | [ADR-004](docs/adr/ADR-004-flyway-migracao.md) |
| Constraint `UNIQUE` + recuperação pós-corrida | Garantia final no banco; SELECT-then-INSERT não basta (TOCTOU) | [ADR-001](docs/adr/ADR-001-estrategia-anti-duplicidade.md) |
| Circuit breaker na borda do provedor (Resilience4j) | Provedor morto não derruba o processamento; fail-fast via fallback 503 | [ADR-006](docs/adr/ADR-006-circuit-breaker-resilience4j.md) |
| Security Resource Server condicional | Dev local sem IdP; produção liga via env var | [ADR-003](docs/adr/ADR-003-seguranca-resource-server.md) |
| Compose só com infraestrutura (app roda local) | Iteração rápida de código sem rebuild Docker | [ADR-005](docs/adr/ADR-005-app-local-compose-infra.md) |

---

## Fluxo de uma ativação

```
POST /api/v1/requests
        │
        ▼
┌───────────────────────────────────────────────────┐
│ 1. Controller valida DTO (Bean Validation)        │
│ 2. Service valida campos (defensivo) e busca      │
│    por externalId                                  │
│    ├─ existe + mesmo conteúdo → 200 (idempotente) │
│    ├─ existe + conteúdo diferente → 409 Conflict  │
│    └─ não existe → INSERT (com UNIQUE constraint) │
│ 3. Após COMMIT → Dispatcher.dispatch(id)          │
│    (TransactionSynchronization.afterCommit)       │
└───────────────────────────┬───────────────────────┘
                            │  (async thread)
                            ▼
┌───────────────────────────────────────────────────┐
│ 4. Processor: marca PROCESSING, chama provedor    │
│ 5. Avalia retorno HTTP:                           │
│    ├─ 2xx        → COMPLETED                      │
│    ├─ 4xx (400)  → FAILED (erro permanente)       │
│    ├─ 5xx/408/429→ retry (backoff linear)         │
│    └─ timeout    → retry                          │
│ 6. Retry esgotado → FAILED                        │
└───────────────────────────────────────────────────┘
```

---

## Como executar

### Pré-requisitos

- Java 21
- Docker + Docker Compose

### 1. Subir infraestrutura (PostgreSQL + mock do provedor)

```bash
docker compose up -d
```

Isso sobe:
- **PostgreSQL 16** em `localhost:5432`
- **WireMock** (stub do provedor) em `localhost:9090`

> **Sobre o mock em 9090:** o WireMock é um stub que só responde a `POST /provider/v1/activate`. Ao acessar `http://localhost:9090` no navegador (que faz `GET /`), ele retorna 404/403 pois não existe mapeamento para `GET /`. Isso é esperado — o mock simula a API do provedor, não é uma aplicação com UI. Para inspecionar os mappings, acesse `http://localhost:9090/__admin`.

### 2. Rodar a aplicação localmente

```bash
export DB_URL=jdbc:postgresql://localhost:5432/product_activation
export DB_USERNAME=app
export DB_PASSWORD=dev_password
export PROVIDER_BASE_URL=http://localhost:9090
./mvnw spring-boot:run
```

A aplicação fica em `http://localhost:8080`.

### 3. Rodar testes

```bash
./mvnw verify
```

Executa testes unitários (JUnit 5 + Mockito), testes de arquitetura (ArchUnit), testes de API (`@WebMvcTest` + MockMvc) e testes de integração com Testcontainers (PostgreSQL real) e stub do provedor. **71 testes, 0 falhas.** O mesmo comando roda no CI: GitHub Actions (`.github/workflows/ci.yml`) executa `./mvnw verify` a cada push/PR na `main`, com cache do Maven e upload dos relatórios em caso de falha.

### Ambiente completo via Docker (produção / smoke test)

O `Dockerfile` multi-stage builda a imagem. Para subir o app containerizado (ex.: validar a imagem antes de subir pipeline):

```bash
docker build -t product-activation .
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/product_activation \
  -e DB_USERNAME=app \
  -e DB_PASSWORD=dev_password \
  -e PROVIDER_BASE_URL=http://host.docker.internal:9090 \
  product-activation
```

Ou reintroduza o serviço `app` no `compose.yml` sob um profile (ver [ADR-005](docs/adr/ADR-005-app-local-compose-infra.md)).

---

## Endpoints

| Método | Rota | Escopo | Descrição |
|--------|------|--------|-----------|
| POST | `/api/v1/requests` | `activation:write` | Cria solicitação (201 + `Location`; 200 em retry idempotente; 409 em conflito; 400 em dados inválidos) |
| GET | `/api/v1/requests/{id}` | `activation:read` | Consulta por id (404 se inexistente) |
| GET | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | Listagem paginada com filtros |
| GET | `/actuator/health` | público | Health check |

### Exemplo de uso

```bash
# Criar solicitação
curl -X POST http://localhost:8080/api/v1/requests \
  -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

# Resposta: 201 Created
# {
#   "id": "0b7f3c1e-...",
#   "externalId": "EXT-12345",
#   "customerId": "CUST-10001",
#   "productCode": "INTERNET-500",
#   "status": "PENDING",
#   "createdAt": "2026-10-26T14:03:11Z",
#   "updatedAt": "2026-10-26T14:03:11Z"
# }

# Consultar status (após processamento assíncrono, status muda para COMPLETED)
curl http://localhost:8080/api/v1/requests/{id}
```

---

## Decisões técnicas

### Estratégia anti-duplicidade (item 3)

- **Constraint UNIQUE** em `external_id` no banco (`uk_activations_external_id`) — é a garantia final, pois "verificar e depois inserir" sozinho não evita corrida entre duas transações (TOCTOU).
- **Tratamento de corrida**: se o `INSERT` violar a constraint, a transação é revertida e uma **nova transação** reconsulta o registro. Assim a query de recuperação não roda em transação abortada (erro `23505` + `current transaction is aborted` do PostgreSQL).

**Decisão de semântica** — alinhada ao Item 3 do desafio: o obrigatório é que o sistema "não deve criar duas solicitações com o mesmo `externalId`"; a resposta em caso de repetição é decisão do candidato (o enunciado lista "200 com o registro existente, ou 409?" — este projeto adota as duas, conforme o cenário):

| Cenário | Resposta HTTP | Comportamento |
|---------|---------------|---------------|
| Mesmo `externalId` + mesmo conteúdo (`customerId` + `productCode`) | **200 OK** | Retorna a ativação existente, sem criar outra nem reprocessar. |
| Mesmo `externalId` + conteúdo divergente | **409 Conflict** | Rejeita com Problem Details; evita que um retry incorreto altere silenciosamente a intenção original. |
| Duas requisições idênticas em paralelo | **201** (uma) + **200** (demais) | Constraint UNIQUE garante um único registro; as perdedoras recebem o registro vencedor via recuperação de corrida. |

**Por que 200 no retry legítimo?** A repetição não é conflito de negócio: a operação já foi realizada, e devolver o registro existente permite que o cliente recupere o resultado sem duplicar a ativação. O `externalId` é a chave de idempotência — por isso **não** entra na comparação do conteúdo; comparamos apenas `customerId` e `productCode`.

### Contrato do serviço (ActivationService ↔ ActivationServiceImpl)

A interface `ActivationService` declara exatamente o contrato público — todo método abstrato tem implementação em `ActivationServiceImpl`:

| Método na interface | Responsabilidade |
|---------------------|------------------|
| `create(ActivationRequestDTO)` | Valida campos, aplica a regra anti-duplicidade, persiste e agenda o dispatch pós-commit. Retorna `CreateActivationResult`. |
| `findById(UUID)` | Consulta por id (`404` se inexistente). |
| `list(status, customerId, pageable)` | Listagem paginada com filtros opcionais. |

Os demais métodos da implementação (`validateRequest`, `createInTransaction`, `createNew`, `resolveExisting`, `recoverFromRace`, `registerAfterCommitDispatch`) são **privados** — detalhes internos do fluxo de criação (validação, transação, corrida, dispatch); não pertencem ao contrato.

**Por que `CreateActivationResult` existe?** O controller precisa distinguir entre **criou** (201 Created) e **reencontrou um retry idempotente** (200 OK). O record `(dto, created)` carrega exatamente essa distinção; sem ele, o service devolveria apenas o DTO e o status HTTP ficaria ambíguo.

### Fluxo de validação

1. **Bean Validation no controller** (`@Valid` + `@NotBlank`/`@Size`/`@Pattern` no `ActivationRequestDTO`) → `400` com lista de erros por campo.
2. **Regras de negócio no service** (defensivas — valem também para chamadas diretas, fora do HTTP):
   - Campos não-brancos após `trim` → `InvalidActivationRequestException` → `400`;
   - `externalId` já existente com conteúdo divergente → `DuplicateActivationException` → `409`;
   - `externalId` já existente com mesmo conteúdo → registro existente → `200` (retry idempotente);
   - Id inexistente → `ActivationNotFoundException` → `404`.
3. **`GlobalExceptionHandler`** converte as exceptions em Problem Details (RFC 9457) — nunca stack trace nem detalhes internos ao consumidor. Erros internos do processamento assíncrono (ex.: provedor fora do ar) aparecem apenas nos logs do servidor, nunca na resposta da API.

### Integração com o provedor (item 5)

- **Assíncrona pós-commit, sem mensageria**: a API grava e retorna 201 imediatamente; a chamada ao provedor acontece após o `COMMIT` (via `TransactionSynchronization.afterCommit`), evitando manter transação de banco aberta durante HTTP. O assíncrono é um **`ThreadPoolTaskExecutor` local** (4 threads, fila de 100 — `AsyncConfig`); **não há Kafka/RabbitMQ/SQS** no meio do fluxo. Fila de mensageria é uma melhoria futura para escalar workers em múltiplas instâncias (ver [Microsserviços e cloud](#microsserviços-e-cloud-diferencial)).
- **Observação sobre logs**: se o provedor (WireMock) não estiver no ar (`docker compose up -d`), o processamento do **primeiro** POST registra warnings/retries no terminal e o registro termina `FAILED` — isso é esperado e não tem relação com POSTs repetidos, que são atendidos pelo caminho idempotente (200) sem novo processamento.
- **Cliente `RestClient`** com timeouts de conexão e leitura configuráveis (`PROVIDER_CONNECT_TIMEOUT_MS`, `PROVIDER_READ_TIMEOUT_MS`).
- **Idempotência**: header `Idempotency-Key` enviado com o `externalId`, evitando ativação duplicada em timeout ambíguo (o provedor pode ter processado; ao reenviar com a mesma chave ele deduplica).
- **Comportamento por retorno**:

  | Retorno | Status final | Nova tentativa? | Justificativa |
  |---------|--------------|-----------------|---------------|
  | Sucesso (2xx) | `COMPLETED` | — | Ativação confirmada |
  | HTTP 400/4xx | `FAILED` | Não | Erro permanente de dados; reenviar não resolve |
  | HTTP 500 | `PENDING` (retry) até 3 tentativas, depois `FAILED` | Sim | Falha transitória provável |
  | Timeout / rede | `PENDING` (retry) até 3 tentativas, depois `FAILED` | Sim | Falha transitória; `Idempotency-Key` protege contra duplicidade |
  | HTTP 429/408/503 | `PENDING` (retry) com backoff | Sim | Rate limit / temporário |

- **Retry com backoff linear** (`backoff-ms * tentativa`), limite de tentativas em `app.provider.max-attempts`.
- **Header `Retry-After` honrado**: quando o provedor responde `429`/`503` com `Retry-After: <segundos>`, o dispatcher usa esse valor como delay da próxima tentativa no lugar do backoff linear — com teto `app.provider.retry-after-max-ms` (30s, para não prender o worker num delay abusivo do provedor). Valor não numérico (ex.: data HTTP) é ignorado e cai no backoff padrão.
- **Circuit breaker (Resilience4j)** — [ADR-006](docs/adr/ADR-006-circuit-breaker-resilience4j.md): `@CircuitBreaker(name = "provider")` envolve `RestClientActivationProviderClient.activate`, a única porta de saída para o provedor. Falhas registradas: exceções de rede e respostas HTTP `>= 500`. Com o breaker aberto, o `fallbackMethod` devolve `503` sintético, que segue o mesmo caminho de retry (fail-fast, sem tocar a rede). Config: janela COUNT_BASED de 10, mínimo 5 chamadas, 50% de falhas, 10s em open, 3 em half-open. Estado em `/actuator/circuitbreakers`.
- **Modo de processamento** configurável: `ASYNC` (default), `SYNC` ou `DISABLED` (testes).

### Transições de estado (domínio)

```
PENDING → PROCESSING → COMPLETED
                      → FAILED
         PROCESSING → PENDING (retry)
```

Transições controladas por métodos de domínio (`markProcessing`, `markCompleted`, `markFailed`, `backToPending`) que lançam `InvalidStatusTransitionException` para estados inválidos. `COMPLETED` é terminal. Lock otimista via `@Version` + constraint UNIQUE.

### Erros (item 6)

Problem Details (RFC 9457) via `ProblemDetail` do Spring 6:

- 400 validação (lista de erros por campo)
- 404 inexistente
- 409 duplicidade / transição inválida
- 429 rate limit excedido (`RequestNotPermitted` do Resilience4j)
- 500 genérico (sem stack trace)
- Header `X-Trace-Id` para correlação (propagado para logs)

### Rate limiting (diferencial)

- `@RateLimiter(name = "api")` (Resilience4j) nos três endpoints do controller: **100 requisições/min por instância** por default (`RATE_LIMIT_PER_MINUTE`), sem espera (`timeout-duration=0`) — excedeu, responde `429` Problem Detail na hora.
- Limites por instância (cada réplica conta a si mesma); em produção o limite real sairia do gateway ou por consumer via JWT — o plugin é um piso de proteção, não a política final.
- Estado/métricas em `/actuator/ratelimiters`.
- Teste de integração: `ActivationRateLimitIntegrationTest` sobe o contexto com `limit-for-period=2` e valida `200, 200, 429`.

### Segurança (item 7)

- Spring Security como **OAuth2 Resource Server** (JWT), ativado via `SECURITY_ENABLED=true`.
- Escopos: `activation:write` (POST) e `activation:read` (GET).
- Health do actuator sempre público. Para habilitar: `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` ou `...JWK_SET_URI`.
- Credencial do provedor externo: variável de ambiente (em produção, secret manager do cloud).

### Banco de dados (Flyway)

- Migração `V1__create_activations_table.sql` com constraints, checks e índices.
- Hibernate em `validate` — nunca gerencia schema.
- Índices: `status`, `customer_id`, `(status, customer_id)`, `created_at DESC`.

### Estratégia de testes

| Nível | Ferramenta | O que cobre |
|-------|-----------|-------------|
| Unitário (sem Spring) | JUnit 5 + Mockito | Transições de domínio, lógica do `ActivationProcessor` (incl. `Retry-After` → `ProcessResult`), delay/cap do `ActivationDispatcher`, validação e regras do `ActivationService` (duplicidade, corrida, trim) |
| Arquitetura | ArchUnit (`ArchitectureTest`) | Controller não acessa `repository`/`integration`; service não conhece controller; `model` não depende de camada superior; `repository` só interfaces; `dto` não acessa persistência/integração |
| Web | `@WebMvcTest` + MockMvc | Validação de DTO, status codes, Problem Details, segurança (401/403) |
| Integração | Testcontainers (PostgreSQL) + stub do provedor | Criação real, corrida concorrente (8 threads → 1 registro), processamento assíncrono (Awaitility), circuit breaker abrindo com provedor falhando (fail-fast sem chamada de rede), rate limit respondendo 429 |

---

## Cenário de análise — produção (item 10)

**O que investigaria primeiro?** Latência do serviço externo (400ms → 3s) e das consultas (50ms → 1,2s) — os três sintomas podem ter **uma causa comum**: conexões do pool de banco esgotadas ou contenção de I/O.

**Como identificaria a camada?**
1. Métricas Micrometer: latência HTTP da API, latência/taxa de erro do provedor, `hikaricp_connections_pending`.
2. Logs correlacionados por `X-Trace-Id` medindo tempo de cada etapa (DB vs HTTP).
3. `pg_stat_statements` para queries lentas; thread dump para I/O bloqueado.

**Logs/métricas úteis:** percentis p95/p99 de latência, pool Hikari (ativo/pendente), taxa de timeout do provedor, uso de CPU por GC.

**Melhorias:** índices conforme filtros (já criados: status, customer, composto, created_at); cache do provedor; circuit breaker + backoff exponencial com jitter; separar worker de processamento; upgrade de instance se CPU/Memória forem limitantes.

**Hipótese de causa comum:** pool de conexões do Hikari saturado por queries lentas (sem índice ou plano ruim) → API espera conexão (latência sobe), e chamadas HTTP seguram conexões abertas → timeouts do provedor aumentam → filas crescem. Confirmaria com métricas do pool + `pg_stat_activity`.

**Validação pós-correção:** comparar p95 antes/depois, taxa de erro, tempo de recuperação; monitorar por 48h com baseline.

---

## Produção (item 11)

- Imagem multi-stage (`eclipse-temurin:21-jdk-alpine` build → `jre-alpine` runtime), usuário **non-root**, `JAVA_OPTS` com `MaxRAMPercentage=75`.
- Health checks, `server.shutdown=graceful`, dependências ordenadas no Compose.
- Configuração 100% por variáveis de ambiente. Segredos via secret manager (AWS Secrets Manager / Vault / GCP Secret Manager).
- CI: pipeline GitHub Actions implementada (`.github/workflows/ci.yml`) — build e testes com `./mvnw verify` a cada push/PR, cache do Maven e artefato com os relatórios de teste quando algo falha.
- CD: pipeline de build de imagem, publicação no registry e deploy via rolling update — descrito como evolução da CI acima.

---

## Microsserviços e cloud (diferencial)

- **Múltiplas instâncias**: unicidade garantida pelo banco (constraint UNIQUE) — funciona com N réplicas. Processamento via fila (SQS/Kafka) em vez de thread pool local para escalar workers.
- **Segredos**: variáveis de ambiente injetadas pelo orquestrador (ECS/EKS), nunca no repositório.
- **Notificação de conclusão**: publicar evento em tópico (SNS/SQS/Kafka) quando status vira `COMPLETED`/`FAILED`.
- **CI/CD**: GitHub Actions → testes → build de imagem → ECR → deploy automático.

---

## Pontos pendentes

- Isolamento entre consumidores (BOLA) — requer claims no JWT mapeadas por tenant.
- Rate limit por consumidor — o limite atual é por instância; para limite por tenant/consumer seria preciso keyar o `RateLimiter` via JWT (melhoria natural do `@RateLimiter` atual).

---

## Melhorias futuras

- Job de reconciliação para solicitações presas em `PROCESSING`.
- Métricas de negócio (solicitações por status, latência do provedor) exportadas ao Prometheus.
- Logs estruturados em JSON + OpenTelemetry tracing.
- Reintroduzir serviço `app` no compose via profile `full` para smoke test da imagem.

---

## Uso de IA

Assistente de IA utilizado para: análise do enunciado, diagnóstico do codebase, geração de migração Flyway, implementação e testes. Decisões de arquitetura (semântica 200/409, retry pós-commit, Resource Server condicional) foram tomadas/revisadas manualmente.
