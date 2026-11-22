-- Fase 2B: políticas RLS granulares, privilegios mínimos de `app_runtime` y corrección
-- segura del trigger de continuidad administrativa bajo RLS real.
--
-- No modifica V1 ni V2. No concede BYPASSRLS, SUPERUSER ni propiedad de tablas a `app_runtime`.
-- No abre acceso a `PUBLIC`, `anon` ni `authenticated`.
-- Mantiene cerradas la creación/borrado de empresas (flujo de plataforma en 2C) y el borrado
-- físico de perfiles y membresías.

-- 1. Funciones auxiliares booleanas de autorización con alcance mínimo (evitan recursión RLS
--    al consultar `nexora.company_memberships` desde políticas sobre la misma tabla).
--    Propiedad de `app_migrator` (NOSUPERUSER, NOBYPASSRLS, NOINHERIT), search_path fijado
--    a `pg_catalog`, nombres calificados, sin SQL dinámico y derivando siempre el actor
--    desde `nexora.current_user_id()` (nunca aceptan un user_id controlado por el cliente).

CREATE OR REPLACE FUNCTION nexora.is_active_company_member(p_company_id uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_user_id uuid;
BEGIN
    v_user_id := nexora.current_user_id();
    IF v_user_id IS NULL OR p_company_id IS NULL THEN
        RETURN false;
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM nexora.companies c
        JOIN nexora.company_memberships m
          ON m.company_id = c.id
        WHERE c.id = p_company_id
          AND c.status = 'active'
          AND m.user_id = v_user_id
          AND m.status = 'active'
    );
END;
$$;

CREATE OR REPLACE FUNCTION nexora.is_active_company_admin(p_company_id uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_user_id uuid;
BEGIN
    v_user_id := nexora.current_user_id();
    IF v_user_id IS NULL OR p_company_id IS NULL THEN
        RETURN false;
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM nexora.companies c
        JOIN nexora.company_memberships m
          ON m.company_id = c.id
        WHERE c.id = p_company_id
          AND c.status = 'active'
          AND m.user_id = v_user_id
          AND m.role = 'company_admin'
          AND m.status = 'active'
    );
END;
$$;

REVOKE ALL ON FUNCTION nexora.is_active_company_member(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION nexora.is_active_company_admin(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION nexora.is_active_company_member(uuid) TO app_runtime;
GRANT EXECUTE ON FUNCTION nexora.is_active_company_admin(uuid) TO app_runtime;

-- 2. Corrección del trigger de continuidad del último administrador activo bajo RLS real:
--    Con SECURITY INVOKER, una autosuspensión o sustitución atómica ejecutada como `app_runtime`
--    perdía visibilidad RLS sobre `nexora.companies` o sobre las demás membresías activas al
--    evaluar la restricción diferida en COMMIT. Pasarlo a SECURITY DEFINER (propietario
--    `app_migrator`, sin SQL dinámico, search_path fijo y solo invocable como trigger)
--    garantiza el bloqueo FOR UPDATE y el conteo real de administradores activos.

CREATE OR REPLACE FUNCTION nexora.enforce_company_has_active_admin()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    target_company_id uuid;
    company_status text;
    has_active_admin boolean;
BEGIN
    IF TG_TABLE_NAME = 'companies' THEN
        target_company_id := NEW.id;
    ELSE
        target_company_id := OLD.company_id;
    END IF;

    SELECT c.status
    INTO company_status
    FROM nexora.companies c
    WHERE c.id = target_company_id
    FOR UPDATE;

    IF company_status = 'active' THEN
        SELECT EXISTS (
            SELECT 1
            FROM nexora.company_memberships m
            WHERE m.company_id = target_company_id
              AND m.role = 'company_admin'
              AND m.status = 'active'
        )
        INTO has_active_admin;

        IF NOT has_active_admin THEN
            RAISE EXCEPTION
                USING ERRCODE = '23514',
                      MESSAGE = 'Active company must have at least one active company_admin';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION nexora.enforce_company_has_active_admin() FROM PUBLIC;

-- 3. Privilegios mínimos por tabla y columna para `app_runtime`.
--    - `profiles`: sin DELETE; UPDATE solo en campos editables (id y created_at inmutables).
--    - `companies`: sin INSERT ni DELETE (alta exclusiva de plataforma en 2C);
--      UPDATE solo en `name` y `updated_at` (id, slug, status y created_at protegidos).
--    - `company_memberships`: sin DELETE (baja por estado); UPDATE solo en `role`, `status`
--      y `updated_at` (company_id, user_id y created_at inmutables).

GRANT SELECT ON TABLE nexora.profiles TO app_runtime;
GRANT INSERT (id, display_name, avatar_path) ON TABLE nexora.profiles TO app_runtime;
GRANT UPDATE (display_name, avatar_path, updated_at) ON TABLE nexora.profiles TO app_runtime;

GRANT SELECT ON TABLE nexora.companies TO app_runtime;
GRANT UPDATE (name, updated_at) ON TABLE nexora.companies TO app_runtime;

GRANT SELECT ON TABLE nexora.company_memberships TO app_runtime;
GRANT INSERT (company_id, user_id, role, status) ON TABLE nexora.company_memberships TO app_runtime;
GRANT UPDATE (role, status, updated_at) ON TABLE nexora.company_memberships TO app_runtime;

-- 4. Políticas RLS explícitas (`USING` y `WITH CHECK`) para `app_runtime`.

-- 4.1 nexora.profiles: lectura, alta idempotente y edición estrictamente del propio perfil.
CREATE POLICY profiles_select_own
    ON nexora.profiles
    FOR SELECT
    TO app_runtime
    USING (
        nexora.current_user_id() IS NOT NULL
        AND id = nexora.current_user_id()
    );

CREATE POLICY profiles_insert_own
    ON nexora.profiles
    FOR INSERT
    TO app_runtime
    WITH CHECK (
        nexora.current_user_id() IS NOT NULL
        AND id = nexora.current_user_id()
    );

CREATE POLICY profiles_update_own
    ON nexora.profiles
    FOR UPDATE
    TO app_runtime
    USING (
        nexora.current_user_id() IS NOT NULL
        AND id = nexora.current_user_id()
    )
    WITH CHECK (
        nexora.current_user_id() IS NOT NULL
        AND id = nexora.current_user_id()
    );

-- 4.2 nexora.companies: solo miembros activos de empresas activas leen su empresa;
--     solo un company_admin activo de esa empresa puede actualizar campos delegados.
CREATE POLICY companies_select_active_member
    ON nexora.companies
    FOR SELECT
    TO app_runtime
    USING (
        status = 'active'
        AND nexora.is_active_company_member(id)
    );

CREATE POLICY companies_update_active_admin
    ON nexora.companies
    FOR UPDATE
    TO app_runtime
    USING (
        status = 'active'
        AND nexora.is_active_company_admin(id)
    )
    WITH CHECK (
        status = 'active'
        AND nexora.is_active_company_admin(id)
    );

-- 4.3 nexora.company_memberships:
--     - SELECT: membresía propia activa en empresa activa, o todas las membresías de una
--       empresa activa donde el actor es company_admin activo.
--     - INSERT: solo company_admin activo de esa empresa.
--     - UPDATE: solo company_admin activo de esa empresa (USING). WITH CHECK permite también
--       la propia degradación/suspensión del administrador dentro de una sustitución atómica,
--       quedando siempre sujeta al trigger diferido que exige conservar >= 1 admin activo.
CREATE POLICY company_memberships_select_own_active
    ON nexora.company_memberships
    FOR SELECT
    TO app_runtime
    USING (
        nexora.current_user_id() IS NOT NULL
        AND user_id = nexora.current_user_id()
        AND status = 'active'
        AND nexora.is_active_company_member(company_id)
    );

CREATE POLICY company_memberships_select_by_active_admin
    ON nexora.company_memberships
    FOR SELECT
    TO app_runtime
    USING (
        nexora.is_active_company_admin(company_id)
    );

CREATE POLICY company_memberships_insert_by_active_admin
    ON nexora.company_memberships
    FOR INSERT
    TO app_runtime
    WITH CHECK (
        nexora.is_active_company_admin(company_id)
    );

CREATE POLICY company_memberships_update_by_active_admin
    ON nexora.company_memberships
    FOR UPDATE
    TO app_runtime
    USING (
        nexora.is_active_company_admin(company_id)
    )
    WITH CHECK (
        nexora.is_active_company_admin(company_id)
        OR (
            user_id = nexora.current_user_id()
            AND (role <> 'company_admin' OR status <> 'active')
        )
    );

-- 5. Garantía explícita de cero acceso para roles expuestos por Data API (anon / authenticated).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        EXECUTE 'REVOKE ALL ON TABLE nexora.profiles, nexora.companies, nexora.company_memberships FROM anon';
        EXECUTE 'REVOKE ALL ON FUNCTION nexora.current_user_id(), nexora.is_active_company_member(uuid), nexora.is_active_company_admin(uuid), nexora.enforce_company_has_active_admin() FROM anon';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        EXECUTE 'REVOKE ALL ON TABLE nexora.profiles, nexora.companies, nexora.company_memberships FROM authenticated';
        EXECUTE 'REVOKE ALL ON FUNCTION nexora.current_user_id(), nexora.is_active_company_member(uuid), nexora.is_active_company_admin(uuid), nexora.enforce_company_has_active_admin() FROM authenticated';
    END IF;
END $$;
