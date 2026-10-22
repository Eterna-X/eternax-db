# eternax-db

Database access layer for eternaSync services. One Gradle build, one jar
(`com.eternax:eternax-db`). A service adds the jar and calls repository methods. It holds no SQL
and no datasource settings of its own.

```kotlin
implementation("com.eternax:eternax-db:0.1.0")
```

```
eternax-db/
├── build.gradle.kts, settings.gradle.kts     the one build
├── src/main/java/com/eternax/db/
│   ├── common/          shared: DatabaseSettingsLoader, @ConditionalOnServiceDatabase
│   ├── ingestion/       ingestion-service
│   ├── normalizer/      normalizer-service
│   ├── matching/        matching-worker
│   ├── orchestration/   orchestration-service
│   ├── exception/       exception-service
│   ├── adjustment/      adjustment-service
│   ├── audit/           audit-service
│   ├── closure/         closure-service
│   └── auth/            eternaX-auth-service
├── src/main/resources/
│   ├── eternax-db/database.yml     every service's settings (common)
│   └── db/<database>/              each service's migrations (db/ingestion, db/exceptions, ...)
├── src/test/java/com/eternax/db/   tests, same layout
└── build/                          all build output (generated, not committed)
```

| Package | Holds | Used by |
| --- | --- | --- |
| `common` | `DatabaseSettingsLoader`, `database.yml`, `@ConditionalOnServiceDatabase` | every service |
| `ingestion` | `SourceConfigRepository`, `SourceStatusRepository`, `BatchRepository` | ingestion-service |
| `normalizer` | `MappingRepository`, `DltReplayRepository` | normalizer-service |
| `matching` | `MatchingRepository`, `SettlementRepository`, `DefinitionSnapshotRepository` | matching-worker |
| `orchestration` | `DefinitionRepository`, `RunRepository` | orchestration-service |
| `exception` | `CaseRepository`, `TatClockRepository` | exception-service |
| `adjustment` | `AdjustmentRepository`, `ApprovalPolicyRepository` | adjustment-service |
| `audit` | `AuditEventRepository` (storage only; the hash chain stays in audit-service) | audit-service |
| `closure` | `ClosureRepository` | closure-service |
| `auth` | `IamStore` (needs a `DefaultTenantPolicy` bean from auth-service) | eternaX-auth-service |

Each service package also holds the row types its repositories return; its Flyway migrations are
in `src/main/resources/db/<database>`, copied byte for byte from the service.

Every service gets the whole jar, but only its own repositories: each `<Service>DbAutoConfiguration`
is marked `@ConditionalOnServiceDatabase("<database>")`, and Flyway only reads `db/<database>`.
ingestion-service never loads exception-service's queries (`ServiceDatabaseScopeTest` checks this
for all 9).

## How a service gets its settings

All services share one file, `eternax-db/database.yml`. Only the database name differs between
them, so the file maps each `spring.application.name` to its database:

```yaml
eternax.db.databases:
  ingestion-service: ingestion
  exception-service: exceptions
  ...
spring.datasource.url: ${DB_URL:jdbc:postgresql://localhost:5432/${eternax.db.name}}
spring.flyway.locations: classpath:db/${eternax.db.name},classpath:db/platform
```

At startup `DatabaseSettingsLoader` looks up the service's name, sets `eternax.db.name` to its
database (ingestion-service gets `ingestion`) and adds the file. A service missing from the map
stops at startup with a message saying where to add it.

The file is the lowest-precedence source. Its values read `DB_URL`, `DB_USER` and `DB_PASSWORD`
from the environment (docker-compose locally, Kubernetes secrets in a cluster), and anything the
service sets itself overrides it. The defaults in the file are for a developer machine only; never
put a real password in it.

## Adding a service

1. **Config:** add one line under `eternax.db.databases` in
   `src/main/resources/eternax-db/database.yml` (`<spring.application.name>: <database>`).
2. **Code:** put its repositories and the row types they return in
   `src/main/java/com/eternax/db/<service>`, and register them in a `<Service>DbAutoConfiguration`
   marked `@ConditionalOnServiceDatabase("<database>")`. Append that class to
   `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   and add it to `ServiceDatabaseScopeTest`.
3. **Migrations:** copy the service's migrations byte for byte into `src/main/resources/db/<database>/`.
   Flyway checks a checksum of each script it has already applied, so any change to an applied
   script stops the service from starting.
4. **In the service:** add `implementation("com.eternax:eternax-db:<version>")` and
   `includeBuild("../eternax-db")` in its settings.gradle.kts, delete the moved classes and
   migrations, remove `spring.datasource` and `spring.flyway.locations` from its application.yml
   (and its test application.yml), and point imports at `com.eternax.db.<service>`.

## Local development

A service's `settings.gradle.kts` includes `../eternax-db` when that folder exists (a Gradle
composite build). The service then builds against these sources directly: a change here is picked
up by the service's next build, with no publishing step. Without the folder (CI, a fresh clone) the
service uses the published version from its gradle.properties.

```bash
./gradlew build                      # here: format check + tests (embedded PostgreSQL, no Docker needed)
cd ../ingestion-service
./gradlew build                      # builds the eternax-db jar from ../eternax-db, then the service
```

Release: push a tag `vX.Y.Z`; CI publishes the eternax-db jar to GitHub Packages.
