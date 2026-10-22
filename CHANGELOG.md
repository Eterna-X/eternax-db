# Changelog

Semantic versioning. A MAJOR bump changes or removes something a service depends on and is adopted by each service in its own pull request. Add the entry here in the same pull request as the change, then tag `vX.Y.Z` to publish.
## 0.2.1
Use github env for secrets

## 0.2.0
- **Breaking:** `spring.datasource.username` and `password` no longer have a default in `database.yml`; a service must set `DB_USER` and `DB_PASSWORD` (environment, Kubernetes secret, docker-compose or `.env`).
- `DB_HOST`, `DB_PORT` and `DB_NAME` build the URL when `DB_URL` is not set (defaults: localhost, 5432, the service's own database).
- Local development: `DatabaseSettingsLoader` reads a git-ignored `.env` (or the file named by `eternax.db.env-file`) in the service's folder. Order: real environment variables, then `.env`, then `database.yml`. `.env.example` lists the keys.

## 0.1.0
- Initial release: one jar (`com.eternax:eternax-db`) with the database access layer of all 9 services.
- `common`: `DatabaseSettingsLoader`, the shared `database.yml` (service name to database) and `@ConditionalOnServiceDatabase`.
- One package per service (ingestion, normalizer, matching, orchestration, exception, adjustment, audit, closure, auth) with its repositories, row types and Flyway migrations (`db/<database>`, copied byte for byte from the service).
- Depends on `eternax-core` 1.2.1.
