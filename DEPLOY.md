# ALOUTE - Windows + Docker + Supabase deployment

## 1. What runs where

- Spring Boot: Docker container on your Windows machine.
- PostgreSQL: Supabase (external; do NOT run SQL Server for production).
- MongoDB: MongoDB Atlas.
- Images: Cloudinary.
- Email: Gmail SMTP/App Password.
- Optional moderation service: separate service at `MODERATION_SERVICE_URL`.

## 2. Supabase connection

In Supabase Dashboard -> Connect -> Session pooler -> JDBC, copy the JDBC connection string.
For a persistent Spring Boot backend on an IPv4 Windows host, Session pooler on port 5432 is the recommended starting point.

Put it into `.env` as `DB_URL`, and keep the password out of source control.

Example shape:

    DB_URL=jdbc:postgresql://YOUR-POOLER-HOST:5432/postgres?user=postgres.YOUR_PROJECT_REF&password=YOUR_DB_PASSWORD&sslmode=require
    DB_SCHEMA=public

If your DB password contains reserved URL characters (`&`, `#`, `?`, spaces, etc.), percent-encode them in the URL.

## 3. First local production test

    copy .env.example .env
    notepad .env

Fill in at least:

- DB_URL / DB_USERNAME / DB_PASSWORD (or use the username/password embedded in DB_URL and leave separate values empty if your driver setup allows it)
- MONGO_URI
- JWT_SECRET
- Cloudinary values
- Gmail SMTP values

For the first test:

    FRONTEND_BASE_URL=http://localhost
    APP_CORS_ALLOWED_ORIGINS=http://localhost
    APP_COOKIE_SECURE=false

Then:

    docker compose up -d --build
    docker compose ps
    docker compose logs -f app

Open:

    http://localhost:8080

The container exposes port 8080 only on loopback, so it is not directly exposed to the LAN/Internet.

## 4. Public access from another machine on your LAN

Do NOT expose 8080 from this compose file to the public Internet.
Instead use Nginx on Windows (or another reverse proxy) and open only 80/443 in Windows Firewall/router.

For a quick LAN test, you can temporarily change the ports mapping to:

    - "8080:8080"

Then access:

    http://YOUR-WINDOWS-LAN-IP:8080

Do this only on a trusted LAN.

## 5. Domain + HTTPS

For Internet deployment, point your domain DNS to your public IP and put Nginx/Caddy/another reverse proxy in front of Spring Boot.
After HTTPS is working, set:

    FRONTEND_BASE_URL=https://YOUR_DOMAIN
    APP_CORS_ALLOWED_ORIGINS=https://YOUR_DOMAIN
    APP_COOKIE_SECURE=true

WebSocket `/ws` must be proxied with HTTP/1.1 Upgrade headers.

## 6. Database migration

The old `database/*.sql` files are SQL Server scripts and are NOT directly importable into Supabase PostgreSQL.
Because the JPA model is database-agnostic, the cleanest first deployment is to let Hibernate create the PostgreSQL schema with `DB_DDL_AUTO=update`.

Before serious production use, replace `ddl-auto=update` with Flyway/Liquibase migrations and back up Supabase.

## 7. Security

- Do not commit `.env`.
- Rotate any credentials that were previously present in source code.
- Use a strong random JWT secret.
- Use a Gmail App Password, not the normal Gmail account password.
- Restrict CORS to your actual domain.
- Use HTTPS before enabling `APP_COOKIE_SECURE=true`.
