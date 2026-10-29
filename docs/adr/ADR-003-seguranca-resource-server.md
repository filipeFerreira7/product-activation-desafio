# ADR-003: Segurança com OAuth2 Resource Server condicional

## Contexto

A API será consumida por outros sistemas e precisa de autenticação/autorização (JWT + escopos), mas não há servidor de autenticação no escopo do desafio e o dev local não pode depender de um IdP.

## Decisão

- Spring Security como **Resource Server OAuth2**, ativado por propriedade `app.security.enabled` (`SECURITY_ENABLED`).
- Escopos: `activation:write` para `POST`, `activation:read` para `GET`.
- Quando desligado (default local): `permitAll` para desenvolvimento.
- Health do actuator sempre público.
- Credencial do provedor externo via variável de ambiente (secret manager em produção).

## Consequências

- Produção liga `SECURITY_ENABLED=true` + `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` (ou JWK set URI).
- Testes de 401/403 usam `spring-security-test` com token mockado e `JwtDecoder` mockado — sem IdP real.
- Isolamento por consumidor (BOLA) fica para evolução: exigir claim de tenant no JWT e filtrar por ela.
