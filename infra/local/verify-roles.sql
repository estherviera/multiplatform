-- Ejecutar por TCP autenticado, una vez con cada rol y su propia contraseña.
\set ON_ERROR_STOP on
SELECT current_user, session_user, current_setting('server_version') AS postgres;
DO $$
DECLARE
    actor oid := current_user::regrole;
    auth_users oid;
BEGIN
    SELECT c.oid INTO STRICT auth_users FROM pg_class c
        JOIN pg_namespace n ON c.relnamespace = n.oid
        WHERE n.nspname = 'auth' AND c.relname = 'users';
    IF current_user <> session_user OR current_user NOT IN ('app_runtime', 'app_migrator', 'app_platform')
       OR EXISTS (SELECT FROM pg_roles WHERE oid = actor
                  AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication OR rolinherit))
       OR EXISTS (SELECT FROM pg_auth_members WHERE member = actor) THEN
        RAISE EXCEPTION 'Identidad o privilegios inesperados';
    END IF;
    IF current_user = 'app_runtime' THEN
        IF NOT has_schema_privilege(actor, 'nexora', 'USAGE')
           OR has_schema_privilege(actor, 'nexora_platform', 'USAGE')
           OR EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege(actor, oid, 'CREATE'))
           OR EXISTS (SELECT FROM pg_class WHERE relowner = actor)
           OR has_database_privilege(actor, current_database(), 'TEMP')
           OR has_database_privilege(actor, current_database(), 'CREATE')
           OR has_any_column_privilege(actor, auth_users, 'SELECT') THEN
            RAISE EXCEPTION 'El runtime supera los permisos previstos';
        END IF;
    ELSIF current_user = 'app_platform' THEN
        IF NOT has_schema_privilege(actor, 'nexora_platform', 'USAGE')
           OR has_schema_privilege(actor, 'nexora', 'USAGE')
           OR EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege(actor, oid, 'CREATE'))
           OR EXISTS (SELECT FROM pg_class WHERE relowner = actor)
           OR has_database_privilege(actor, current_database(), 'TEMP')
           OR has_database_privilege(actor, current_database(), 'CREATE')
           OR has_any_column_privilege(actor, auth_users, 'SELECT') THEN
            RAISE EXCEPTION 'El ejecutor de plataforma supera los permisos previstos';
        END IF;
    ELSIF NOT has_schema_privilege(actor, 'nexora', 'USAGE')
       OR NOT has_schema_privilege(actor, 'nexora', 'CREATE')
       OR NOT has_schema_privilege(actor, 'nexora_platform', 'CREATE')
       OR NOT has_column_privilege(actor, auth_users, 'id', 'REFERENCES') THEN
        RAISE EXCEPTION 'El migrador carece de los permisos requeridos';
    END IF;
END $$;
