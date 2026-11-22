-- Fase 2C.2: invitaciones empresariales, aceptación atómica segura y auditoría durable.
-- Aditiva sobre V1–V3 (sin modificar migraciones aplicadas ni el esquema interno `auth`).
-- No concede BYPASSRLS, SUPERUSER ni propiedad de tablas a `app_runtime`.
-- Mantiene cerradas la creación/borrado de empresas y cero privilegios para PUBLIC/anon/authenticated.

-- 1. Tabla de invitaciones empresariales con ciclo de vida explícito (sin sobrecargar
--    `company_memberships.status` con significados distintos como rechazo o revocación).
CREATE TABLE nexora.company_invitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES nexora.companies(id) ON DELETE RESTRICT,
    invitee_user_id uuid NOT NULL REFERENCES nexora.profiles(id) ON DELETE RESTRICT,
    invited_by_user_id uuid NOT NULL REFERENCES nexora.profiles(id) ON DELETE RESTRICT,
    role text NOT NULL,
    status text NOT NULL DEFAULT 'pending',
    expires_at timestamptz NOT NULL,
    responded_at timestamptz NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT company_invitations_role_valid CHECK (role IN ('company_admin', 'trainer', 'member')),
    CONSTRAINT company_invitations_status_valid CHECK (status IN ('pending', 'accepted', 'declined', 'revoked')),
    CONSTRAINT company_invitations_expiry_valid CHECK (expires_at > created_at),
    CONSTRAINT company_invitations_responded_valid CHECK (
        (status = 'pending' AND responded_at IS NULL)
        OR (status <> 'pending' AND responded_at IS NOT NULL)
    ),
    CONSTRAINT company_invitations_updated_after_created CHECK (updated_at >= created_at)
);

-- A lo sumo una invitación pendiente por (empresa, destinatario) para idempotencia y concurrencia.
CREATE UNIQUE INDEX uq_company_invitations_pending_unique
    ON nexora.company_invitations (company_id, invitee_user_id)
    WHERE status = 'pending';

CREATE INDEX idx_company_invitations_invitee_status
    ON nexora.company_invitations (invitee_user_id, status, created_at DESC, id ASC);

CREATE INDEX idx_company_invitations_company_status
    ON nexora.company_invitations (company_id, status, created_at DESC, id ASC);

-- 2. Tabla de auditoría durable (solo inserción controlada mediante función dedicada;
--    sin UPDATE ni DELETE para app_runtime).
CREATE TABLE nexora.audit_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_user_id uuid NULL,
    company_id uuid NULL REFERENCES nexora.companies(id) ON DELETE RESTRICT,
    action text NOT NULL,
    target_type text NOT NULL,
    target_id text NOT NULL,
    outcome text NOT NULL,
    correlation_id text NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT audit_events_action_valid CHECK (
        char_length(btrim(action)) BETWEEN 1 AND 80 AND action = btrim(action)
    ),
    CONSTRAINT audit_events_target_type_valid CHECK (
        char_length(btrim(target_type)) BETWEEN 1 AND 64 AND target_type = btrim(target_type)
    ),
    CONSTRAINT audit_events_target_id_valid CHECK (
        char_length(btrim(target_id)) BETWEEN 1 AND 128 AND target_id = btrim(target_id)
    ),
    CONSTRAINT audit_events_outcome_valid CHECK (outcome IN ('succeeded', 'denied', 'failed')),
    CONSTRAINT audit_events_correlation_valid CHECK (
        correlation_id IS NULL
        OR (char_length(btrim(correlation_id)) BETWEEN 1 AND 64 AND correlation_id = btrim(correlation_id))
    )
);

CREATE INDEX idx_audit_events_company_occurred
    ON nexora.audit_events (company_id, occurred_at DESC, id ASC)
    WHERE company_id IS NOT NULL;

CREATE INDEX idx_audit_events_actor_occurred
    ON nexora.audit_events (actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;

-- 3. Funciones auxiliares acotadas SECURITY DEFINER (propietario `app_migrator`,
--    NOSUPERUSER, NOBYPASSRLS, search_path fijo = pg_catalog, sin SQL dinámico).

-- 3.1 Comprobación booleana de elegibilidad de destinatario (existencia de perfil vinculado a auth.users).
--     No expone nombres, avatares, correos ni datos de auth.users.
CREATE OR REPLACE FUNCTION nexora.profile_exists(p_user_id uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF nexora.current_user_id() IS NULL OR p_user_id IS NULL THEN
        RETURN false;
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM nexora.profiles p
        WHERE p.id = p_user_id
    );
END;
$$;

-- 3.2 Registro inmutable de auditoría dentro de la transacción de negocio.
CREATE OR REPLACE FUNCTION nexora.record_audit_event(
    p_company_id uuid,
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
    v_actor uuid;
    v_event_id uuid;
BEGIN
    v_actor := nexora.current_user_id();
    IF v_actor IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Authentication required for audit recording';
    END IF;

    IF p_company_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Company scope is required for tenant audit events';
    END IF;

    IF p_action NOT IN (
        'membership.invited',
        'membership.invitation_declined',
        'membership.invitation_revoked',
        'membership.role_changed',
        'membership.suspended',
        'membership.reactivated',
        'membership.admin_replaced'
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Unsupported audit action';
    END IF;

    IF p_action = 'membership.invitation_declined' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM nexora.company_invitations i
            WHERE i.id = p_target_id::uuid
              AND i.company_id = p_company_id
              AND i.invitee_user_id = v_actor
        ) THEN
            RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Actor is not authorized to audit this invitation';
        END IF;
    ELSE
        IF NOT nexora.is_active_company_admin(p_company_id) THEN
            RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Active company_admin required for administrative audit event';
        END IF;
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
        v_actor,
        p_company_id,
        p_action,
        p_target_type,
        p_target_id,
        p_outcome,
        p_correlation_id,
        COALESCE(p_metadata, '{}'::jsonb)
    )
    RETURNING id INTO v_event_id;

    RETURN v_event_id;
END;
$$;

-- 3.3 Aceptación atómica e idempotente de invitación por su destinatario autenticado.
--     Mantiene `company_memberships` cerrada frente a INSERT/UPDATE directos por no administradores.
CREATE OR REPLACE FUNCTION nexora.accept_company_invitation(
    p_invitation_id uuid,
    p_correlation_id text DEFAULT NULL
)
RETURNS TABLE (
    company_id uuid,
    user_id uuid,
    role text,
    status text,
    created_at timestamptz,
    updated_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_uid uuid;
    v_inv nexora.company_invitations%ROWTYPE;
    v_company_status text;
    v_existing_status text;
    v_now timestamptz := now();
BEGIN
    v_uid := nexora.current_user_id();
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Authentication required';
    END IF;

    SELECT *
    INTO v_inv
    FROM nexora.company_invitations i
    WHERE i.id = p_invitation_id
    FOR UPDATE;

    -- Ocultar existencia si la invitación no existe o pertenece a otro destinatario.
    IF NOT FOUND OR v_inv.invitee_user_id <> v_uid THEN
        RETURN;
    END IF;

    -- Bloquear la empresa objetivo y verificar que sigue activa.
    SELECT c.status
    INTO v_company_status
    FROM nexora.companies c
    WHERE c.id = v_inv.company_id
    FOR UPDATE;

    IF v_company_status IS DISTINCT FROM 'active' THEN
        RETURN;
    END IF;

    -- Idempotencia: si ya fue aceptada previamente, devolver la membresía activa existente.
    IF v_inv.status = 'accepted' THEN
        RETURN QUERY
        SELECT m.company_id, m.user_id, m.role, m.status, m.created_at, m.updated_at
        FROM nexora.company_memberships m
        WHERE m.company_id = v_inv.company_id
          AND m.user_id = v_uid
          AND m.status = 'active';
        RETURN;
    END IF;

    IF v_inv.status <> 'pending' THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invitation_not_pending';
    END IF;

    IF v_inv.expires_at <= v_now THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invitation_expired';
    END IF;

    SELECT m.status
    INTO v_existing_status
    FROM nexora.company_memberships m
    WHERE m.company_id = v_inv.company_id
      AND m.user_id = v_uid
    FOR UPDATE;

    IF v_existing_status = 'suspended' THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'membership_suspended';
    ELSIF v_existing_status = 'active' THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'membership_already_active';
    ELSIF v_existing_status = 'invited' THEN
        UPDATE nexora.company_memberships m
        SET role = v_inv.role,
            status = 'active',
            updated_at = GREATEST(v_now, m.created_at)
        WHERE m.company_id = v_inv.company_id
          AND m.user_id = v_uid;
    ELSE
        INSERT INTO nexora.company_memberships (company_id, user_id, role, status)
        VALUES (v_inv.company_id, v_uid, v_inv.role, 'active');
    END IF;

    UPDATE nexora.company_invitations i
    SET status = 'accepted',
        responded_at = v_now,
        updated_at = GREATEST(v_now, i.created_at)
    WHERE i.id = v_inv.id;

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
        v_uid,
        v_inv.company_id,
        'membership.invitation_accepted',
        'company_invitation',
        v_inv.id::text,
        'succeeded',
        p_correlation_id,
        jsonb_build_object('grantedRole', v_inv.role)
    );

    RETURN QUERY
    SELECT m.company_id, m.user_id, m.role, m.status, m.created_at, m.updated_at
    FROM nexora.company_memberships m
    WHERE m.company_id = v_inv.company_id
      AND m.user_id = v_uid;
END;
$$;

REVOKE ALL ON FUNCTION nexora.profile_exists(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION nexora.record_audit_event(uuid, text, text, text, text, text, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION nexora.accept_company_invitation(uuid, text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION nexora.profile_exists(uuid) TO app_runtime;
GRANT EXECUTE ON FUNCTION nexora.record_audit_event(uuid, text, text, text, text, text, jsonb) TO app_runtime;
GRANT EXECUTE ON FUNCTION nexora.accept_company_invitation(uuid, text) TO app_runtime;

-- 4. RLS y privilegios mínimos en `company_invitations` y `audit_events`.
ALTER TABLE nexora.company_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE nexora.audit_events ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE nexora.company_invitations, nexora.audit_events FROM PUBLIC;
REVOKE ALL ON TABLE nexora.company_invitations, nexora.audit_events FROM app_runtime;

GRANT SELECT ON TABLE nexora.company_invitations TO app_runtime;
GRANT INSERT (company_id, invitee_user_id, invited_by_user_id, role, expires_at) ON TABLE nexora.company_invitations TO app_runtime;
GRANT UPDATE (status, responded_at, updated_at) ON TABLE nexora.company_invitations TO app_runtime;

-- `audit_events`: solo SELECT para administradores activos de su empresa (sin INSERT/UPDATE/DELETE directo).
GRANT SELECT ON TABLE nexora.audit_events TO app_runtime;

CREATE POLICY company_invitations_select_by_admin
    ON nexora.company_invitations
    FOR SELECT
    TO app_runtime
    USING (nexora.is_active_company_admin(company_id));

CREATE POLICY company_invitations_select_by_invitee
    ON nexora.company_invitations
    FOR SELECT
    TO app_runtime
    USING (
        nexora.current_user_id() IS NOT NULL
        AND invitee_user_id = nexora.current_user_id()
    );

CREATE POLICY company_invitations_insert_by_admin
    ON nexora.company_invitations
    FOR INSERT
    TO app_runtime
    WITH CHECK (
        nexora.current_user_id() IS NOT NULL
        AND invited_by_user_id = nexora.current_user_id()
        AND nexora.is_active_company_admin(company_id)
    );

CREATE POLICY company_invitations_decline_by_invitee
    ON nexora.company_invitations
    FOR UPDATE
    TO app_runtime
    USING (
        nexora.current_user_id() IS NOT NULL
        AND invitee_user_id = nexora.current_user_id()
        AND status = 'pending'
    )
    WITH CHECK (
        invitee_user_id = nexora.current_user_id()
        AND status = 'declined'
        AND responded_at IS NOT NULL
    );

CREATE POLICY company_invitations_revoke_by_admin
    ON nexora.company_invitations
    FOR UPDATE
    TO app_runtime
    USING (
        nexora.is_active_company_admin(company_id)
        AND status = 'pending'
    )
    WITH CHECK (
        nexora.is_active_company_admin(company_id)
        AND status = 'revoked'
        AND responded_at IS NOT NULL
    );

CREATE POLICY audit_events_select_by_active_admin
    ON nexora.audit_events
    FOR SELECT
    TO app_runtime
    USING (
        company_id IS NOT NULL
        AND nexora.is_active_company_admin(company_id)
    );

-- 5. Garantía explícita de cero acceso para roles expuestos por Data API (anon / authenticated).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        EXECUTE 'REVOKE ALL ON TABLE nexora.company_invitations, nexora.audit_events FROM anon';
        EXECUTE 'REVOKE ALL ON FUNCTION nexora.profile_exists(uuid), nexora.record_audit_event(uuid, text, text, text, text, text, jsonb), nexora.accept_company_invitation(uuid, text) FROM anon';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        EXECUTE 'REVOKE ALL ON TABLE nexora.company_invitations, nexora.audit_events FROM authenticated';
        EXECUTE 'REVOKE ALL ON FUNCTION nexora.profile_exists(uuid), nexora.record_audit_event(uuid, text, text, text, text, text, jsonb), nexora.accept_company_invitation(uuid, text) FROM authenticated';
    END IF;
END $$;
