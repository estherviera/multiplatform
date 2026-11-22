-- Aprovisionamiento del proyecto Docker desechable, fuera de Flyway.
\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'postgres' OR to_regclass('auth.users') IS NULL THEN
        RAISE EXCEPTION 'Se requiere la base local de pruebas con Auth inicializado';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_runtime') THEN
        CREATE ROLE app_runtime LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB
            NOCREATEROLE NOREPLICATION NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_migrator') THEN
        CREATE ROLE app_migrator LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB
            NOCREATEROLE NOREPLICATION NOINHERIT;
    END IF;
    IF EXISTS (SELECT FROM pg_roles r WHERE rolname IN ('app_runtime', 'app_migrator')
        AND (NOT rolcanlogin OR rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole
             OR rolreplication OR rolinherit OR EXISTS (SELECT FROM pg_auth_members WHERE member = r.oid))) THEN
        RAISE EXCEPTION 'Atributos o membresías inesperados. Revisar antes de continuar';
    END IF;
END $$;

-- Solo en la base aislada de este proyecto. Evita DDL heredado a través de PUBLIC.
REVOKE CREATE, TEMPORARY ON DATABASE postgres FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS nexora AUTHORIZATION app_migrator;
REVOKE ALL ON SCHEMA nexora FROM PUBLIC;
GRANT CONNECT ON DATABASE postgres TO app_runtime, app_migrator;
GRANT USAGE ON SCHEMA nexora TO app_runtime;
GRANT USAGE ON SCHEMA auth TO app_migrator;
GRANT REFERENCES (id) ON auth.users TO app_migrator;
ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

DO $$
BEGIN
    IF (SELECT nspowner FROM pg_namespace WHERE nspname = 'nexora') <> 'app_migrator'::regrole
       OR EXISTS (SELECT FROM pg_class WHERE relowner = 'app_runtime'::regrole)
       OR EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege('app_runtime', oid, 'CREATE'))
       OR has_database_privilege('app_runtime', current_database(), 'CREATE')
       OR has_database_privilege('app_runtime', current_database(), 'TEMP') THEN
        RAISE EXCEPTION 'Propiedad o privilegios de creación inesperados';
    END IF;
END $$;
COMMIT;
