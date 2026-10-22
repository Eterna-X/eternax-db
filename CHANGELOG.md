# Changelog

Semantic versioning. A MAJOR bump changes or removes something a service depends on and is adopted by each service in its own pull request. Add the entry here in the same pull request as the change, then tag `vX.Y.Z` to publish.

## 0.1.0
- Initial release: one jar (`com.eternax:eternax-db`) with the database access layer of all 9 services.
- `common`: `DatabaseSettingsLoader`, the shared `database.yml` (service name to database) and `@ConditionalOnServiceDatabase`.
- One package per service (ingestion, normalizer, matching, orchestration, exception, adjustment, audit, closure, auth) with its repositories, row types and Flyway migrations (`db/<database>`, copied byte for byte from the service).
- Depends on `eternax-core` 1.2.1.
