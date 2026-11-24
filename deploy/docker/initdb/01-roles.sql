-- NoteHive bootstrap: roles + database. Runs once as the postgres superuser
-- during container initialization (docker-entrypoint-initdb.d).
--
--  notehive_migrator  owns the schema, runs Flyway (needs CREATEROLE for the
--                     role guards inside V1 + ADMIN OPTION to GRANT)
--  notehive_app       RLS-bound application role (default datasource)
--  notehive_worker    BYPASSRLS role (worker datasource: bloom rebuild, retention)
--
-- Idempotent on purpose: the compose file sets POSTGRES_DB=notehive, so the
-- docker entrypoint creates the "notehive" database BEFORE this script runs
-- (a bare CREATE DATABASE here therefore aborts the whole init with
-- "database already exists", exit code 3). Re-running this file must also be
-- safe, e.g. to repair a half-initialized volume after a failed first boot.

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'notehive_migrator') THEN
    CREATE ROLE notehive_migrator LOGIN CREATEROLE PASSWORD 'notehive_migrator_pw';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'notehive_app') THEN
    CREATE ROLE notehive_app LOGIN PASSWORD 'notehive_app_pw';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'notehive_worker') THEN
    CREATE ROLE notehive_worker LOGIN BYPASSRLS PASSWORD 'notehive_worker_pw';
  END IF;
END
$$;

-- Create the database only when it does not exist yet, then force the
-- intended owner in every case (entrypoint-created DBs are owned by
-- POSTGRES_USER; Flyway expects notehive_migrator to own the schema).
SELECT 'CREATE DATABASE notehive OWNER notehive_migrator'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'notehive')\gexec
ALTER DATABASE notehive OWNER TO notehive_migrator;

\c notehive
GRANT notehive_app, notehive_worker TO notehive_migrator WITH ADMIN OPTION;
GRANT USAGE ON SCHEMA public TO notehive_app, notehive_worker;
ALTER DATABASE notehive SET synchronous_commit = on;
