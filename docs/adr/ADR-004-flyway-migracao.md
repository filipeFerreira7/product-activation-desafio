# ADR-004: Migração de schema com Flyway

## Contexto

O `application.properties` já vinha com `spring.flyway.enabled=true` e `spring.jpa.hibernate.ddl-auto=validate`, mas a pasta `db/migration` estava vazia — a aplicação falharia ao subir (`no migrations found` + validação de schema impossível).

## Decisão

- Criar `V1__create_activations_table.sql` como fonte de verdade do schema.
- Constraint `UNIQUE` em `external_id` (anti-duplicidade).
- `CHECK` nos enums de status e nos tamanhos dos campos.
- Índices: `status`, `customer_id`, `(status, customer_id)`, `created_at DESC` (filtros da listagem paginada).
- Hibernate permanece em `validate` — nunca gerencia schema em produção.

## Consequências

- Mudanças futuras em `V2__*.sql`, `V3__*.sql` (imutáveis após aplicadas).
- Testes com Testcontainers rodam as mesmas migrações que produção.
