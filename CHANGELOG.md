# Changelog

## 1.0.0
- **New:** `notification` package for notification-service: `NotificationRepository` (people copy, contacts, rules, notifications, deliveries, inbox) and `NotificationDbAutoConfiguration` (`@ConditionalOnServiceDatabase("notifications")`).
- **New:** Flyway migration `db/notifications/V1__notification_schema.sql`: `app_user`, `user_contact`, `notification_rule`, `notification`, `notification_delivery`.
- `database.yml`: `notification-service` uses the `notifications` database.
- Delivery queue: `claimDue` takes `maxAttempts` and never claims a delivery that used them all; `startAttempt` counts an attempt before it is sent; `failAbandoned` gives up deliveries whose last attempt recorded no outcome. `recordSent`, `recordRetry` and `recordFailed` no longer count the attempt.
- Recipients: `activeUser` and `activeUserByLogin` (case-insensitive) for a notification meant for one person; `userExists`.
- auth: contacts (phone, WhatsApp, push device) in `IamStore` and `Iam.ContactView`; migration `db/auth/V2__user_contact.sql`. The number is stored encrypted and only ever shown masked.

## 0.2.2
Testing connection

## 0.2.1
- Use github env for secrets

## 0.2.0
- **Breaking:** `spring.datasource.username` and `password` no longer have a default in `database.yml`; a service must set `DB_USER` and `DB_PASSWORD` (environment, Kubernetes secret, docker-compose or `.env`).
- `DB_HOST`, `DB_PORT` and `DB_NAME` build the URL when `DB_URL` is not set (defaults: localhost, 5432, the service's own database).
- Local development: `DatabaseSettingsLoader` reads a git-ignored `.env` (or the file named by `eternax.db.env-file`) in the service's folder. Order: real environment variables, then `.env`, then `database.yml`. `.env.example` lists the keys.

## 0.1.0
- Initial release: one jar (`com.eternax:eternax-db`) with the database access layer of all 9 services.
- `common`: `DatabaseSettingsLoader`, the shared `database.yml` (service name to database) and `@ConditionalOnServiceDatabase`.
- One package per service (ingestion, normalizer, matching, orchestration, exception, adjustment, audit, closure, auth) with its repositories, row types and Flyway migrations (`db/<database>`, copied byte for byte from the service).
- Depends on `eternax-core` 1.2.1.
