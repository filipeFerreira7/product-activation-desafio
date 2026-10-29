# Diagnóstico Kanban — Product Activation

## TO DO (pendências identificadas na análise)

| # | Item | Origem | Prioridade |
|---|------|--------|------------|
| 1 | `Activation.java` sem import de `@Entity` — não compila | Bug no codebase | Alta |
| 2 | Pasta `db/migration/` vazia com `flyway.enabled=true` + `ddl-auto=validate` — app falha ao subir | Bug no codebase | Alta |
| 3 | `Dockerfile` placeholder (`ubuntu` + `top`) — não executa a aplicação | Bug no codebase | Alta |
| 4 | Sem controller, service, repository, DTOs | Lacuna vs desafio (itens 2, 4) | Alta |
| 5 | Sem integração com provedor externo (item 5) | Lacuna vs desafio | Alta |
| 6 | Sem tratamento de erros / Problem Details (item 6) | Lacuna vs desafio | Alta |
| 7 | Sem segurança configurada (item 7) | Lacuna vs desafio | Média |
| 8 | Sem testes além de `contextLoads` (item 8) | Lacuna vs desafio | Alta |
| 9 | Sem README com seções obrigatórias (entrega) | Lacuna vs desafio | Alta |
| 10 | Versão de referência: `JpaRepository<Activation, Long>` vs `@Id UUID` — incompatível | Bug (projeto desafio) | Alta |
| 11 | Versão de referência: typo `costumerId` em vez de `customerId` (contrato da API) | Bug (projeto desafio) | Alta |
| 12 | Versão de referência: POST retorna 200 em vez de 201 + `Location` | Bug (projeto desafio) | Média |
| 13 | Versão de referência: `RuntimeException` genérica → 500 em vez de 404 | Bug (projeto desafio) | Alta |
| 14 | Versão de referência: Spring Security default bloqueia a API sem config | Bug (projeto desafio) | Alta |
| 15 | Versão de referência: retorna entidade JPA direto no controller | Bug (projeto desafio) | Média |
| 16 | Versão de referência: sem paginação/filtros no GET list | Lacuna (diferencial item 2) | Baixa |
| 17 | Versão de referência: status sem transições controladas | Lacuna (diferencial item 2) | Média |
| 18 | Versão de referência: sem `@Version` / lock otimista | Lacuna (diferencial) | Baixa |

## DOING (em execução nesta entrega)

| # | Item | Status |
|---|------|--------|
| 1 | Flyway `V1__create_activations_table.sql` com constraints, checks e índices | ✅ |
| 2 | Ajustes de `pom.xml` (OAuth2 resource server, Testcontainers, Awaitility, spring-security-test) | ✅ |
| 3 | `application.properties` (provider, async, security, actuator, graceful shutdown) | ✅ |
| 4 | Domínio `Activation` com transições encapsuladas + `@Version` | ✅ |
| 5 | API REST: POST 201/`Location`, GET by id 404, list paginado com filtros | ✅ |
| 6 | Anti-duplicidade: UNIQUE + recuperação pós-corrida em nova transação | ✅ |
| 7 | Integração assíncrona pós-commit com `RestClient`, timeouts, idempotency key, retry/backoff | ✅ |
| 8 | Problem Details (RFC 9457) + `X-Trace-Id` de correlação | ✅ |
| 9 | Security Resource Server condicional com escopos read/write | ✅ |
| 10 | Dockerfile multi-stage (non-root) + Compose (app + postgres + WireMock) | ✅ |
| 11 | Testes: domínio, serviço, processor, MockMvc, segurança, integração Testcontainers (incl. corrida) | ✅ |
| 12 | README + ADRs | ✅ |

## DONE (finalizado e verificado)

- `./mvnw test` → **48 testes, 0 falhas** (inclui corrida concorrente com 8 threads → exatamente 1 registro).
- Flyway migra com sucesso (`ddl-auto=validate` confere entidade × schema).
- Compilação em Java 21 / Spring Boot 3.5.16.

## Bugs encontrados e corrigidos durante os testes

1. **Race no INSERT único**: `catch (DataIntegrityViolationException)` + `findByExternalId` na mesma transação falhava com `current transaction is aborted` (PostgreSQL). Corrigido: recuperação em **nova transação** fora do `catch`.
2. **`afterCommit` + `@Transactional`**: chamada síncrona pós-commit falhava com `no transaction is in progress`. Corrigido com `TransactionTemplate` + `PROPAGATION_REQUIRES_NEW`.
3. **Retry nunca reexecutava**: `processOnce` era chamado uma vez. Corrigido com loop de retries + backoff no `ActivationDispatcher`.
4. **Contador de chamadas do stub compartilhado entre testes** de integração — reset por teste.

## Arquitetura recomendada (adotada)

Camadas simples `controller → service → repository` (o desafio avalia simplicidade, não over-engineering), com:

- **Domínio rico**: transições e regras de vida nos métodos da entidade (não setters livres).
- **DTOs records** separados da entidade JPA; validação nos DTOs.
- **Assíncrono pós-commit** para a chamada remota (não segura conexão de banco durante HTTP).
- **Flyway** como fonte de verdade do schema; Hibernate apenas `validate`.
- **Problem Details** + correlação de erro.
- **Security Resource Server** condicional (desligado em dev local).
- Testes em 3 níveis: unitário (sem Spring), web (MockMvc), integração (Testcontainers + stub do provedor).
