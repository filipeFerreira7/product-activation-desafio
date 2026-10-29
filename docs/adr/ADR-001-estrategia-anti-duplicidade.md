# ADR-001: Estratégia anti-duplicidade em `externalId`

## Contexto

O sistema não pode criar duas solicitações com o mesmo `externalId`, inclusive com requisições paralelas. "Verificar se existe e depois inserir" sozinho não resolve: entre o SELECT e o INSERT outra transação pode inserir (TOCTOU).

## Decisão

1. Constraint `UNIQUE (external_id)` no banco — garantia final.
2. Pré-verificação por `findByExternalId` para o caminho comum.
3. Se o `INSERT` violar a constraint, reverter a transação e **reconsultar em uma nova transação** (recuperação de corrida).
4. Semântica de resposta:
   - mesmo conteúdo → `200 OK` com o registro existente (retry idempotente);
   - conteúdo divergente → `409 Conflict`.

## Consequências

- Funciona com N instâncias da aplicação (regra no banco, não em memória).
- Queries de recuperação nunca rodam em transação abortada.
- Consumidor precisa tratar 200 como possível "já existia" (corpo idêntico ao esperado).
