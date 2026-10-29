# ADR-002: Processamento assíncrono pós-commit da integração

## Contexto

A API deve chamar o provedor externo (`POST /provider/v1/activate`) após criar a solicitação. Chamar HTTP dentro de transação de banco abre riscos: conexão segurada durante I/O lento, e se o app cair entre commit e chamada a solicitação fica `PENDING` sem ser enviada.

## Decisão

- A API grava, retorna `201` e **não** espera o provedor.
- Após o `COMMIT` (`TransactionSynchronization.afterCommit`), um dispatcher processa em thread própria (`ASYNC`) ou síncrono (`SYNC`, testes).
- Cliente `RestClient` com timeouts de conexão/leitura explícitos.
- Header `Idempotency-Key` = `externalId` para deduplicação no provedor em timeout ambíguo.
- Retry com backoff e limite de tentativas (`PENDING → PROCESSING → PENDING...` até esgotar → `FAILED`).

## Consequências

- Latência de criação independente do provedor.
- Se a aplicação cair após commit, a solicitação permanece `PENDING` — em produção, um job de reconciliação reprocessaria (melhoria futura).
- Garantia "gravado será enviado" no caminho normal; para falha entre commit e dispatch, reconciliação é o mecanismo descrito no README.
