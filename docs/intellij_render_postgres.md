# IntelliJ / DataGrip connection to Render PostgreSQL

For the backend running inside Render, use the **Internal Database URL** in the backend environment variable:

```text
PFI_DATABASE_URL=<Internal Database URL from Render PostgreSQL>
PFI_PERSISTENCE_MODE=postgres
```

For IntelliJ, DataGrip, DBeaver or pgAdmin running from your local computer, use the **External Database URL** or the separated connection fields from Render.

## Recommended IntelliJ settings

Use `Connection type: default`, not `URL only`.

Fill the fields separately:

```text
Host: <External hostname from Render>
Port: 5432
Database: pfi_mvptest_enzo_postgres
User: pfi_mvptest_enzo_postgres_user
Password: <Render database password>
```

In `SSH/SSL` or advanced connection properties, enable SSL or add:

```text
sslmode=require
```

## JDBC URL format

If IntelliJ asks for a JDBC URL, do not use this format:

```text
jdbc:postgresql://user:password@host:5432/database
```

That URI-style username/password syntax is valid for many platform URLs, but IntelliJ's PostgreSQL JDBC parser expects the credentials outside the host portion.

Use this format instead:

```text
jdbc:postgresql://<external-host>:5432/pfi_mvptest_enzo_postgres?sslmode=require&user=<username>&password=<password>
```

Or preferably keep the URL simple and put user/password in the UI fields:

```text
jdbc:postgresql://<external-host>:5432/pfi_mvptest_enzo_postgres?sslmode=require
```

## Notes

- The backend should use Render's **Internal Database URL**.
- Local tools should use Render's **External Database URL** or separated fields.
- Do not commit real passwords or full database URLs.
