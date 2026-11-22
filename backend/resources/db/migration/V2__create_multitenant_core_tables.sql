-- Fase 2A: esquema multiempresa inicial en el esquema privado `nexora`.
-- Crea únicamente:
--   - nexora.profiles (vinculada 1:1 a auth.users(id), sin contraseñas ni secretos)
--   - nexora.companies (raíz multi-tenant)
--   - nexora.company_memberships (relación N:M usuario <-> empresa con rol y estado por empresa)
--
-- Habilita RLS con denegación por defecto (sin políticas permisivas ni grants amplios
-- a `app_runtime`). Las políticas RLS granulares, los grants mínimos de operación y los
-- endpoints corresponden a la Fase 2B.

CREATE TABLE nexora.profiles (
    id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE RESTRICT,
    display_name text NOT NULL,
    avatar_path text NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT profiles_display_name_valid CHECK (
        char_length(btrim(display_name)) BETWEEN 1 AND 120
        AND display_name = btrim(display_name)
    ),
    CONSTRAINT profiles_avatar_path_valid CHECK (
        avatar_path IS NULL
        OR (
            char_length(btrim(avatar_path)) BETWEEN 1 AND 512
            AND avatar_path = btrim(avatar_path)
            AND position('..' in avatar_path) = 0
        )
    ),
    CONSTRAINT profiles_updated_after_created CHECK (updated_at >= created_at)
);

CREATE TABLE nexora.companies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug text NOT NULL,
    name text NOT NULL,
    status text NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT companies_slug_unique UNIQUE (slug),
    CONSTRAINT companies_slug_format CHECK (
        char_length(slug) BETWEEN 2 AND 63
        AND slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
    ),
    CONSTRAINT companies_name_valid CHECK (
        char_length(btrim(name)) BETWEEN 1 AND 120
        AND name = btrim(name)
    ),
    CONSTRAINT companies_status_valid CHECK (status IN ('active', 'suspended')),
    CONSTRAINT companies_updated_after_created CHECK (updated_at >= created_at)
);

CREATE INDEX idx_companies_status
    ON nexora.companies (status);

CREATE TABLE nexora.company_memberships (
    company_id uuid NOT NULL REFERENCES nexora.companies(id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES nexora.profiles(id) ON DELETE RESTRICT,
    role text NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT company_memberships_pkey PRIMARY KEY (company_id, user_id),
    CONSTRAINT company_memberships_role_valid CHECK (role IN ('company_admin', 'trainer', 'member')),
    CONSTRAINT company_memberships_status_valid CHECK (status IN ('invited', 'active', 'suspended')),
    CONSTRAINT company_memberships_updated_after_created CHECK (updated_at >= created_at)
);

-- Índice inverso para descubrir membresías/empresas activas de un usuario y acelerar políticas RLS futuras.
CREATE INDEX idx_company_memberships_user_status
    ON nexora.company_memberships (user_id, status, company_id);

-- Índice parcial específico para verificar y consultar administradores activos por empresa.
CREATE INDEX idx_company_memberships_company_active_admins
    ON nexora.company_memberships (company_id, user_id)
    WHERE role = 'company_admin' AND status = 'active';

-- Índice de apoyo para listados internos por empresa, rol y estado.
CREATE INDEX idx_company_memberships_company_role_status
    ON nexora.company_memberships (company_id, role, status);

-- Invariante de continuidad administrativa:
-- Toda empresa con status = 'active' debe conservar al menos una membresía
-- con role = 'company_admin' y status = 'active' al confirmar la transacción.
-- El bloqueo FOR UPDATE sobre la fila de `nexora.companies` serializa cambios concurrentes.
CREATE OR REPLACE FUNCTION nexora.enforce_company_has_active_admin()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog
AS $$
DECLARE
    target_company_id uuid;
    company_status text;
    has_active_admin boolean;
BEGIN
    IF TG_TABLE_NAME = 'companies' THEN
        target_company_id := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        target_company_id := OLD.company_id;
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

CREATE CONSTRAINT TRIGGER trg_companies_require_active_admin
    AFTER INSERT OR UPDATE OF status ON nexora.companies
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status = 'active')
    EXECUTE FUNCTION nexora.enforce_company_has_active_admin();

CREATE CONSTRAINT TRIGGER trg_company_memberships_require_active_admin
    AFTER UPDATE OF company_id, role, status OR DELETE ON nexora.company_memberships
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.role = 'company_admin' AND OLD.status = 'active')
    EXECUTE FUNCTION nexora.enforce_company_has_active_admin();

-- RLS habilitado en todas las tablas privadas con denegación por defecto (0 políticas permisivas).
ALTER TABLE nexora.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE nexora.companies ENABLE ROW LEVEL SECURITY;
ALTER TABLE nexora.company_memberships ENABLE ROW LEVEL SECURITY;

-- Sin privilegios para PUBLIC ni para app_runtime en Fase 2A (grants granulares y políticas en Fase 2B).
REVOKE ALL ON TABLE nexora.profiles, nexora.companies, nexora.company_memberships FROM PUBLIC;
REVOKE ALL ON TABLE nexora.profiles, nexora.companies, nexora.company_memberships FROM app_runtime;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        EXECUTE 'REVOKE ALL ON TABLE nexora.profiles, nexora.companies, nexora.company_memberships FROM anon';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        EXECUTE 'REVOKE ALL ON TABLE nexora.profiles, nexora.companies, nexora.company_memberships FROM authenticated';
    END IF;
END $$;
