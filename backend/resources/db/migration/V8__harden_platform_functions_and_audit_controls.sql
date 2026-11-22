-- Fase 2C.3C: endurecimiento adversarial de las funciones SQL de plataforma tras auditoría.
-- Aditiva sobre V1–V7 (sin modificar migraciones anteriores ni ampliar privilegios).
--
-- Correcciones aplicadas:
-- 1. Corrige el recorte de `p_correlation_id` (`btrim(left(btrim(...), 64))`) para que una cadena
--    >64 caracteres con un espacio en la posición 64 nunca provoque fallo `23514` en `audit_events`.
-- 2. Restringe `p_target_type` en `record_platform_audit_event` a lista blanca cerrada
--    (`platform_companies`, `company`) y exige `p_metadata` objeto JSON <= 4096 bytes.
-- 3. Reafirma privilegios mínimos exclusivos para `app_platform` (cero acceso a PUBLIC/app_runtime/anon/authenticated).

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
    v_corr text := NULLIF(btrim(left(btrim(p_correlation_id), 64)), '');
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
    v_corr text := NULLIF(btrim(left(btrim(p_correlation_id), 64)), '');
    v_meta jsonb := COALESCE(p_metadata, '{}'::jsonb);
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

    IF p_target_type NOT IN ('platform_companies', 'company') THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Unsupported platform audit target type';
    END IF;

    IF pg_catalog.jsonb_typeof(v_meta) <> 'object'
        OR pg_catalog.octet_length(v_meta::text) > 4096 THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Invalid platform audit metadata';
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
        v_meta
    )
    RETURNING id INTO v_event_id;

    RETURN v_event_id;
END;
$$;

REVOKE ALL ON FUNCTION nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION nexora_platform.record_platform_audit_event(uuid, text, text, text, text, text, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text) FROM app_runtime;
REVOKE ALL ON FUNCTION nexora_platform.record_platform_audit_event(uuid, text, text, text, text, text, jsonb) FROM app_runtime;
GRANT EXECUTE ON FUNCTION nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text) TO app_platform;
GRANT EXECUTE ON FUNCTION nexora_platform.record_platform_audit_event(uuid, text, text, text, text, text, jsonb) TO app_platform;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        EXECUTE 'REVOKE ALL ON FUNCTION nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text), nexora_platform.record_platform_audit_event(uuid, text, text, text, text, text, jsonb) FROM anon';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        EXECUTE 'REVOKE ALL ON FUNCTION nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text), nexora_platform.record_platform_audit_event(uuid, text, text, text, text, text, jsonb) FROM authenticated';
    END IF;
END $$;
