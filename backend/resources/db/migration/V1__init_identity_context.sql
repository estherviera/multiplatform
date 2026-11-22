-- Fase 1C: migración inicial de infraestructura para el contexto transaccional de identidad.
-- Propósito: inicializar el historial de Flyway en el esquema privado `nexora` y proporcionar
-- un lector tipado y seguro de `app.user_id` (establecido mediante set_config(..., true)).
-- No crea tablas de negocio ni políticas RLS (reservadas para la Fase 2).

CREATE OR REPLACE FUNCTION nexora.current_user_id()
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog
AS $$
DECLARE
    raw_value text;
BEGIN
    raw_value := current_setting('app.user_id', true);
    IF raw_value IS NULL OR btrim(raw_value) = '' THEN
        RETURN NULL;
    END IF;
    RETURN btrim(raw_value)::uuid;
END;
$$;

REVOKE ALL ON FUNCTION nexora.current_user_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION nexora.current_user_id() TO app_runtime;
