-- Fase 2C.3B: ejecutor SQL acotado de plataforma para creación atómica e idempotente de empresas
-- y auditoría duradera de plataforma (éxitos, denegaciones y fallos).
-- Aditiva sobre V1–V6 (sin modificar migraciones anteriores ni ampliar permisos de `app_runtime`).
--
-- Límite de confianza:
-- - Ktor + DefaultGlobalAdminAuthorizer verifican JWT ES256/JWKS, `NEXORA_GLOBAL_ADMIN_SUB`,
--   sesión viva en Supabase Auth (`GET /user`), MFA `aal2` y frescura `amr` antes de invocar al ejecutor.
-- - El ejecutor SQL conecta con la credencial dedicada `app_platform` (sin SUPERUSER, BYPASSRLS,
--   propiedad de tablas ni acceso al esquema `nexora`), que solo tiene EXECUTE sobre las dos funciones
--   fijas de `nexora_platform`.
-- - `app_runtime`, `PUBLIC`, `anon` y `authenticated` carecen de USAGE sobre `nexora_platform` y de
--   EXECUTE sobre sus funciones.

-- 1. Tabla de idempotencia persistente de operaciones de plataforma (sin acceso directo para roles de ejecución).
CREATE TABLE nexora_platform.idempotency_keys (
    actor_user_id uuid NOT NULL,
    operation text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash text NOT NULL,
    company_id uuid NOT NULL REFERENCES nexora.companies(id) ON DELETE RESTRICT,
    initial_admin_user_id uuid NOT NULL REFERENCES nexora.profiles(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_idempotency_keys_pkey PRIMARY KEY (actor_user_id, operation, idempotency_key),
    CONSTRAINT platform_idempotency_operation_valid CHECK (operation IN ('platform.company.create')),
    CONSTRAINT platform_idempotency_key_valid CHECK (
        char_length(btrim(idempotency_key)) BETWEEN 1 AND 128
        AND idempotency_key = btrim(idempotency_key)
    ),
    CONSTRAINT platform_idempotency_hash_valid CHECK (request_hash ~ '^[a-f0-9]{64}$')
);

ALTER TABLE nexora_platform.idempotency_keys ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE nexora_platform.idempotency_keys FROM PUBLIC;
REVOKE ALL ON TABLE nexora_platform.idempotency_keys FROM app_runtime;
REVOKE ALL ON TABLE nexora_platform.idempotency_keys FROM app_platform;

-- 2. Creación atómica e idempotente de empresa + primer `company_admin` activo + evento de auditoría.
--    Respeta íntegramente `trg_companies_require_active_admin` (sin desactivarlo ni debilitarlo).
CREATE OR REPLACE FUNCTION nexora_platform.create_company_with_initial_admin(
    p_actor_user_id uuid,
    p_idempotency_key text,
    p_request_hash text,
    p_slug text,
    p_name text,
    p_initial_admin_user_id uuid,
    p_correlation_id text DEFAULT NULL
)
RETURNS TABLE (
    replayed boolean,
    company_id uuid,
    company_slug text,
    company_name text,
    company_status text,
    company_created_at timestamptz,
    company_updated_at timestamptz,
    admin_user_id uuid,
    admin_role text,
    admin_status text,
    admin_created_at timestamptz,
    admin_updated_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_key text := btrim(COALESCE(p_idempotency_key, ''));
    v_hash text := lower(btrim(COALESCE(p_request_hash, '')));
    v_slug text := btrim(COALESCE(p_slug, ''));
    v_name text := btrim(COALESCE(p_name, ''));
    v_corr text := NULLIF(left(btrim(p_correlation_id), 64), '');
    v_existing nexora_platform.idempotency_keys%ROWTYPE;
    v_company nexora.companies%ROWTYPE;
    v_membership nexora.company_memberships%ROWTYPE;
BEGIN
    IF p_actor_user_id IS NULL OR p_initial_admin_user_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invalid_platform_arguments';
    END IF;

    IF char_length(v_key) < 1 OR char_length(v_key) > 128 THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invalid_idempotency_key';
    END IF;

    IF v_hash !~ '^[a-f0-9]{64}$' THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invalid_request_hash';
    END IF;

    IF char_length(v_slug) < 2 OR char_length(v_slug) > 63 OR v_slug !~ '^[a-z0-9]+(?:-[a-z0-9]+)*$' THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invalid_company_slug';
    END IF;

    IF char_length(v_name) < 1 OR char_length(v_name) > 120 THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invalid_company_name';
    END IF;

    -- Serializa reintentos concurrentes con la misma pareja (actor, idempotency_key).
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtext('nexora_platform.company.create'),
        pg_catalog.hashtext(p_actor_user_id::text || ':' || v_key)
    );

    SELECT *
    INTO v_existing
    FROM nexora_platform.idempotency_keys ik
    WHERE ik.actor_user_id = p_actor_user_id
      AND ik.operation = 'platform.company.create'
      AND ik.idempotency_key = v_key;

    IF FOUND THEN
        IF v_existing.request_hash <> v_hash THEN
            RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'idempotency_key_conflict';
        END IF;

        SELECT * INTO STRICT v_company
        FROM nexora.companies c
        WHERE c.id = v_existing.company_id;

        SELECT * INTO STRICT v_membership
        FROM nexora.company_memberships m
        WHERE m.company_id = v_existing.company_id
          AND m.user_id = v_existing.initial_admin_user_id;

        RETURN QUERY
        SELECT
            true,
            v_company.id,
            v_company.slug,
            v_company.name,
            v_company.status,
            v_company.created_at,
            v_company.updated_at,
            v_membership.user_id,
            v_membership.role,
            v_membership.status,
            v_membership.created_at,
            v_membership.updated_at;
        RETURN;
    END IF;

    -- Verifica que el primer administrador tiene perfil existente vinculado a auth.users(id).
    IF NOT EXISTS (
        SELECT 1
        FROM nexora.profiles p
        WHERE p.id = p_initial_admin_user_id
    ) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'initial_admin_not_eligible';
    END IF;

    INSERT INTO nexora.companies (slug, name, status)
    VALUES (v_slug, v_name, 'active')
    RETURNING * INTO v_company;

    INSERT INTO nexora.company_memberships (company_id, user_id, role, status)
    VALUES (v_company.id, p_initial_admin_user_id, 'company_admin', 'active')
    RETURNING * INTO v_membership;

    INSERT INTO nexora.audit_events (
        actor_user_id,
        company_id,
        action,
        target_type,
        target_id,
        outcome,
        correlation_id,
        metadata
    )
    VALUES (
        p_actor_user_id,
        v_company.id,
        'platform.company_created',
        'company',
        v_company.id::text,
        'succeeded',
        v_corr,
        pg_catalog.jsonb_build_object(
            'slug', v_company.slug,
            'initialAdminUserId', p_initial_admin_user_id,
            'idempotencyKey', v_key
        )
    );

    INSERT INTO nexora_platform.idempotency_keys (
        actor_user_id,
        operation,
        idempotency_key,
        request_hash,
        company_id,
        initial_admin_user_id
    )
    VALUES (
        p_actor_user_id,
        'platform.company.create',
        v_key,
        v_hash,
        v_company.id,
        p_initial_admin_user_id
    );

    RETURN QUERY
    SELECT
        false,
        v_company.id,
        v_company.slug,
        v_company.name,
        v_company.status,
        v_company.created_at,
        v_company.updated_at,
        v_membership.user_id,
        v_membership.role,
        v_membership.status,
        v_membership.created_at,
        v_membership.updated_at;
END;
$$;

-- 3. Registro durable en transacción separada para intentos denegados, fallidos o limitados por cuota
--    en operaciones de plataforma (invocable exclusivamente por `app_platform`).
CREATE OR REPLACE FUNCTION nexora_platform.record_platform_audit_event(
    p_actor_user_id uuid,
    p_action text,
    p_target_type text,
    p_target_id text,
    p_outcome text,
    p_correlation_id text DEFAULT NULL,
    p_metadata jsonb DEFAULT '{}'::jsonb
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_corr text := NULLIF(left(btrim(p_correlation_id), 64), '');
    v_event_id uuid;
BEGIN
    IF p_action NOT IN (
        'platform.company_create_denied',
        'platform.company_create_failed',
        'platform.company_create_rate_limited'
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Unsupported platform audit action';
    END IF;

    IF p_outcome NOT IN ('denied', 'failed') THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Unsupported platform audit outcome';
    END IF;

    INSERT INTO nexora.audit_events (
        actor_user_id,
        company_id,
        action,
        target_type,
        target_id,
        outcome,
        correlation_id,
        metadata
    )
    VALUES (
        p_actor_user_id,
        NULL,
        p_action,
        p_target_type,
        p_target_id,
        p_outcome,
        v_corr,
        COALESCE(p_metadata, '{}'::jsonb)
    )
    RETURNING id INTO v_event_id;

    RETURN v_event_id;
END;
$$;

-- 4. Privilegios estrictamente mínimos: solo `app_platform` puede ejecutar estas dos funciones.
REVOKE ALL ON FUNCTION nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION nexora_platform.record_platform_audit_event(uuid, text, text, text, text, text, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text) FROM app_runtime;
REVOKE ALL ON FUNCTION nexora_platform.record_platform_audit_event(uuid, text, text, text, text, text, jsonb) FROM app_runtime;

GRANT EXECUTE ON FUNCTION nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text) TO app_platform;
GRANT EXECUTE ON FUNCTION nexora_platform.record_platform_audit_event(uuid, text, text, text, text, text, jsonb) TO app_platform;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        EXECUTE 'REVOKE ALL ON SCHEMA nexora_platform FROM anon';
        EXECUTE 'REVOKE ALL ON TABLE nexora_platform.idempotency_keys FROM anon';
        EXECUTE 'REVOKE ALL ON FUNCTION nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text), nexora_platform.record_platform_audit_event(uuid, text, text, text, text, text, jsonb) FROM anon';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        EXECUTE 'REVOKE ALL ON SCHEMA nexora_platform FROM authenticated';
        EXECUTE 'REVOKE ALL ON TABLE nexora_platform.idempotency_keys FROM authenticated';
        EXECUTE 'REVOKE ALL ON FUNCTION nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text), nexora_platform.record_platform_audit_event(uuid, text, text, text, text, text, jsonb) FROM authenticated';
    END IF;
END $$;
