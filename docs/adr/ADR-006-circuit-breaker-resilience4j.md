# ADR-006: Circuit breaker (Resilience4j) na chamada ao provedor

## Contexto

O provedor externo pode ficar lento ou indisponível. Sem proteção, cada nova ativação continua tentando HTTP contra um destino morto: threads do pool ficam presas em timeouts, as tentativas locais esgotam devagar e a fila cresce.

## Decisão

- Circuit breaker **Resilience4j** (`resilience4j-spring-boot3`) envolvendo a única porta de saída para o provedor: `RestClientActivationProviderClient.activate` (`@CircuitBreaker(name = "provider")`).
- **Falhas registradas**: exceções de rede (default do Resilience4j — qualquer `RuntimeException`) e respostas HTTP `>= 500` (via `CircuitBreakerConfigCustomizer` em `ResilienceConfig`, pois o cliente usa `exchange()` e não lança exceção em 5xx).
- **Breaker aberto**: `fallbackMethod` devolve `ProviderResponse(503)`, que entra no caminho de retry já existente (503 = transitório) — nenhuma mudança no `ActivationProcessor`.
- **Configuração** (properties, ajustável por env): janela COUNT_BASED de 10 chamadas, mínimo 5, failure-rate 50%, 10s em open, 3 chamadas em half-open.
- Estado e métricas expostos no actuator: `/actuator/circuitbreakers`.

## Consequências

- Provedor indisponível não recebe chamada em cascata; o fail-fast do breaker é logado (`WARN`) e a ativação esgota as tentativas locais → `FAILED`.
- Ativações que falham com o breaker aberto terminam `FAILED`; em produção, um job de reconciliação ou fila com redelivery as reprocessaria (melhoria futura, já descrita no README).
- Em half-open o breaker libera chamadas devagar; se o provedor voltar, ele fecha sozinho sem intervenção.
