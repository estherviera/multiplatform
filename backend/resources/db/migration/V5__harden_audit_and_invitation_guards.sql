-- Fase 2C.2 (endurecimiento): auditoría infalsificable mediante triggers y funciones atómicas,
-- revocación de `record_audit_event` a `app_runtime` y guarda de transiciones en `company_invitations`.
-- Aditiva sobre V1–V4 (sin modificar migraciones ya aplicadas).

-- 1. Impedir que `app_runtime` invoque directamente `nexora.record_audit_event(...)` para fabricar
--    eventos de auditoría sin mutación real de negocio.
REVOKE ALL ON FUNCTION nexora.record_audit_event(uuid, text, text, text, text, text, jsonb) FROM app_runtime;
REVOKE ALL ON FUNCTION nexora.record_audit_event(uuid, text, text, text, text, text, jsonb) FROM PUBLIC;

-- 2. Guarda de inmutabilidad y transiciones válidas en `nexora.company_invitations` a nivel de tabla
--    (protege frente a SQL directo además de las políticas RLS).
CREATE OR REPLACE FUNCTION nexora.guard_company_invitation_transition()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.company_id IS DISTINCT FROM OLD.company_id
        OR NEW.invitee_user_id IS DISTINCT FROM OLD.invitee_user_id
        OR NEW.invited_by_user_id IS DISTINCT FROM OLD.invited_by_user_id
        OR NEW.role IS DISTINCT FROM OLD.role
        OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Immutable company_invitations columns cannot be modified';
    END IF;

    IF OLD.status <> 'pending' THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invitation_terminal_state';
    END IF;

    IF NEW.status NOT IN ('accepted', 'declined', 'revoked') THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invitation_invalid_transition';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION nexora.guard_company_invitation_transition() FROM PUBLIC;

CREATE TRIGGER trg_company_invitations_guard_transition
    BEFORE UPDATE ON nexora.company_invitations
    FOR EACH ROW
    EXECUTE FUNCTION nexora.guard_company_invitation_transition();

-- 3. Triggers de auditoría infalsificables (SECURITY DEFINER propiedad de `app_migrator`,
--    search_path = pg_catalog, sin SQL dinámico ni canal de supresión por GUC).
CREATE OR REPLACE FUNCTION nexora.audit_company_invitation_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_actor uuid;
    v_corr text;
BEGIN
    v_actor := nexora.current_user_id();
    IF v_actor IS NULL THEN
        RETURN NULL;
    END IF;

    v_corr := NULLIF(left(btrim(current_setting('app.correlation_id', true)), 64), '');

    IF TG_OP = 'INSERT' THEN
        INSERT INTO nexora.audit_events (
            actor_user_id, company_id, action, target_type, target_id, outcome, correlation_id, metadata
        )
        VALUES (
            v_actor,
            NEW.company_id,
            'membership.invited',
            'company_invitation',
            NEW.id::text,
            'succeeded',
            v_corr,
            jsonb_build_object('inviteeUserId', NEW.invitee_user_id, 'role', NEW.role)
        );
    ELSIF TG_OP = 'UPDATE' AND OLD.status = 'pending' AND NEW.status = 'declined' THEN
        INSERT INTO nexora.audit_events (
            actor_user_id, company_id, action, target_type, target_id, outcome, correlation_id, metadata
        )
        VALUES (
            v_actor,
            NEW.company_id,
            'membership.invitation_declined',
            'company_invitation',
            NEW.id::text,
            'succeeded',
            v_corr,
            jsonb_build_object('role', NEW.role)
        );
    ELSIF TG_OP = 'UPDATE' AND OLD.status = 'pending' AND NEW.status = 'revoked' THEN
        INSERT INTO nexora.audit_events (
            actor_user_id, company_id, action, target_type, target_id, outcome, correlation_id, metadata
        )
        VALUES (
            v_actor,
            NEW.company_id,
            'membership.invitation_revoked',
            'company_invitation',
            NEW.id::text,
            'succeeded',
            v_corr,
            jsonb_build_object('role', NEW.role)
        );
    END IF;

    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION nexora.audit_company_invitation_change() FROM PUBLIC;

CREATE TRIGGER trg_company_invitations_audit
    AFTER INSERT OR UPDATE ON nexora.company_invitations
    FOR EACH ROW
    EXECUTE FUNCTION nexora.audit_company_invitation_change();

CREATE OR REPLACE FUNCTION nexora.audit_company_membership_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_actor uuid;
    v_corr text;
BEGIN
    v_actor := nexora.current_user_id();
    IF v_actor IS NULL THEN
        RETURN NULL;
    END IF;

    v_corr := NULLIF(left(btrim(current_setting('app.correlation_id', true)), 64), '');

    IF OLD.role IS DISTINCT FROM NEW.role THEN
        INSERT INTO nexora.audit_events (
            actor_user_id, company_id, action, target_type, target_id, outcome, correlation_id, metadata
        )
        VALUES (
            v_actor,
            NEW.company_id,
            'membership.role_changed',
            'company_membership',
            NEW.user_id::text,
            'succeeded',
            v_corr,
            jsonb_build_object('previousRole', OLD.role, 'newRole', NEW.role)
        );
    END IF;

    IF OLD.status = 'active' AND NEW.status = 'suspended' THEN
        INSERT INTO nexora.audit_events (
            actor_user_id, company_id, action, target_type, target_id, outcome, correlation_id, metadata
        )
        VALUES (
            v_actor,
            NEW.company_id,
            'membership.suspended',
            'company_membership',
            NEW.user_id::text,
            'succeeded',
            v_corr,
            jsonb_build_object('role', NEW.role)
        );
    ELSIF OLD.status = 'suspended' AND NEW.status = 'active' THEN
        INSERT INTO nexora.audit_events (
            actor_user_id, company_id, action, target_type, target_id, outcome, correlation_id, metadata
        )
        VALUES (
            v_actor,
            NEW.company_id,
            'membership.reactivated',
            'company_membership',
            NEW.user_id::text,
            'succeeded',
            v_corr,
            jsonb_build_object('role', NEW.role)
        );
    END IF;

    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION nexora.audit_company_membership_change() FROM PUBLIC;

CREATE TRIGGER trg_company_memberships_audit
    AFTER UPDATE ON nexora.company_memberships
    FOR EACH ROW
    EXECUTE FUNCTION nexora.audit_company_membership_change();

-- 4. Endurecer `accept_company_invitation` normalizando `p_correlation_id`.
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
    v_corr text := NULLIF(left(btrim(p_correlation_id), 64), '');
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

    IF NOT FOUND OR v_inv.invitee_user_id <> v_uid THEN
        RETURN;
    END IF;

    SELECT c.status
    INTO v_company_status
    FROM nexora.companies c
    WHERE c.id = v_inv.company_id
    FOR UPDATE;

    IF v_company_status IS DISTINCT FROM 'active' THEN
        RETURN;
    END IF;

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
        v_corr,
        jsonb_build_object('grantedRole', v_inv.role)
    );

    RETURN QUERY
    SELECT m.company_id, m.user_id, m.role, m.status, m.created_at, m.updated_at
    FROM nexora.company_memberships m
    WHERE m.company_id = v_inv.company_id
      AND m.user_id = v_uid;
END;
$$;

-- 5. Sustitución atómica y auditable de administrador en una única función SECURITY DEFINER.
CREATE OR REPLACE FUNCTION nexora.replace_company_admin(
    p_company_id uuid,
    p_outgoing_admin_user_id uuid,
    p_successor_user_id uuid,
    p_outgoing_new_role text,
    p_correlation_id text DEFAULT NULL
)
RETURNS TABLE (
    member_kind text,
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
    v_actor uuid;
    v_company_status text;
    v_first_id uuid;
    v_second_id uuid;
    v_outgoing nexora.company_memberships%ROWTYPE;
    v_successor nexora.company_memberships%ROWTYPE;
    v_now timestamptz := now();
    v_corr text := NULLIF(left(btrim(p_correlation_id), 64), '');
BEGIN
    v_actor := nexora.current_user_id();
    IF v_actor IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Authentication required';
    END IF;

    IF p_company_id IS NULL OR p_outgoing_admin_user_id IS NULL OR p_successor_user_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invalid_replace_admin_arguments';
    END IF;

    IF p_outgoing_admin_user_id = p_successor_user_id OR p_outgoing_new_role NOT IN ('trainer', 'member') THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'invalid_replace_admin_arguments';
    END IF;

    IF NOT nexora.is_active_company_admin(p_company_id) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Active company_admin required';
    END IF;

    SELECT c.status
    INTO v_company_status
    FROM nexora.companies c
    WHERE c.id = p_company_id
    FOR UPDATE;

    IF v_company_status IS DISTINCT FROM 'active' THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Company not active';
    END IF;

    -- Bloqueo determinístico por UUID para evitar deadlocks bajo concurrencia.
    IF p_outgoing_admin_user_id::text <= p_successor_user_id::text THEN
        v_first_id := p_outgoing_admin_user_id;
        v_second_id := p_successor_user_id;
    ELSE
        v_first_id := p_successor_user_id;
        v_second_id := p_outgoing_admin_user_id;
    END IF;

    PERFORM 1
    FROM nexora.company_memberships m
    WHERE m.company_id = p_company_id AND m.user_id = v_first_id
    FOR UPDATE;

    PERFORM 1
    FROM nexora.company_memberships m
    WHERE m.company_id = p_company_id AND m.user_id = v_second_id
    FOR UPDATE;

    SELECT * INTO v_outgoing
    FROM nexora.company_memberships m
    WHERE m.company_id = p_company_id AND m.user_id = p_outgoing_admin_user_id;

    SELECT * INTO v_successor
    FROM nexora.company_memberships m
    WHERE m.company_id = p_company_id AND m.user_id = p_successor_user_id;

    IF v_outgoing.user_id IS NULL OR v_outgoing.role <> 'company_admin' OR v_outgoing.status <> 'active' THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'outgoing_not_active_admin';
    END IF;

    IF v_successor.user_id IS NULL OR v_successor.status <> 'active' THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'successor_not_active_member';
    END IF;

    IF v_successor.role <> 'company_admin' THEN
        UPDATE nexora.company_memberships m
        SET role = 'company_admin',
            updated_at = GREATEST(v_now, m.created_at)
        WHERE m.company_id = p_company_id AND m.user_id = p_successor_user_id
        RETURNING * INTO v_successor;
    END IF;

    UPDATE nexora.company_memberships m
    SET role = p_outgoing_new_role,
        updated_at = GREATEST(v_now, m.created_at)
    WHERE m.company_id = p_company_id AND m.user_id = p_outgoing_admin_user_id
    RETURNING * INTO v_outgoing;

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
        'membership.admin_replaced',
        'company_membership',
        p_outgoing_admin_user_id::text,
        'succeeded',
        v_corr,
        jsonb_build_object(
            'outgoingAdminUserId', p_outgoing_admin_user_id,
            'successorUserId', p_successor_user_id,
            'outgoingNewRole', p_outgoing_new_role
        )
    );

    RETURN QUERY
    SELECT 'outgoing'::text, v_outgoing.company_id, v_outgoing.user_id, v_outgoing.role, v_outgoing.status, v_outgoing.created_at, v_outgoing.updated_at
    UNION ALL
    SELECT 'successor'::text, v_successor.company_id, v_successor.user_id, v_successor.role, v_successor.status, v_successor.created_at, v_successor.updated_at;
END;
$$;

REVOKE ALL ON FUNCTION nexora.replace_company_admin(uuid, uuid, uuid, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION nexora.replace_company_admin(uuid, uuid, uuid, text, text) TO app_runtime;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        EXECUTE 'REVOKE ALL ON FUNCTION nexora.guard_company_invitation_transition(), nexora.audit_company_invitation_change(), nexora.audit_company_membership_change(), nexora.replace_company_admin(uuid, uuid, uuid, text, text) FROM anon';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        EXECUTE 'REVOKE ALL ON FUNCTION nexora.guard_company_invitation_transition(), nexora.audit_company_invitation_change(), nexora.audit_company_membership_change(), nexora.replace_company_admin(uuid, uuid, uuid, text, text) FROM authenticated';
    END IF;
END $$;
