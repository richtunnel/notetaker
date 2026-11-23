-- NoteHive bootstrap: roles + database. Runs once as the postgres superuser
-- during container initialization (docker-entrypoint-initdb.d).
--
--  notehive_migrator  owns the schema, runs Flyway (needs CREATEROLE for the
--                     role guards inside V1 + ADMIN OPTION to GRANT)
--  notehive_app       RLS-bound application role (default datasource)
--  notehive_worker    BYPASSRLS role (worker datasource: bloom rebuild, retention)
CREATE ROLE notehive_migrator LOGIN CREATEROLE PASSWORD 'notehive_migrator_pw';
CREATE ROLE notehive_app LOGIN PASSWORD 'notehive_app_pw';
CREATE ROLE notehive_worker LOGIN BYPASSRLS PASSWORD 'notehive_worker_pw';

CREATE DATABASE notehive OWNER notehive_migrator;

\c notehive
GRANT notehive_app, notehive_worker TO notehive_migrator WITH ADMIN OPTION;
GRANT USAGE ON SCHEMA public TO notehive_app, notehive_worker;
ALTER DATABASE notehive SET synchronous_commit = on;
