-- Fase 2C.2 (endurecimiento): diferir el trigger de auditoría de `company_memberships`
-- para ejecutarse en COMMIT después de `trg_company_memberships_require_active_admin`.
-- Evita deadlocks (40P01) entre el bloqueo FOR KEY SHARE de la FK `audit_events.company_id`
-- y el bloqueo FOR UPDATE diferido del trigger de continuidad del último administrador activo.

DROP TRIGGER IF EXISTS trg_company_memberships_audit ON nexora.company_memberships;

CREATE CONSTRAINT TRIGGER trg_z_company_memberships_audit
    AFTER UPDATE ON nexora.company_memberships
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION nexora.audit_company_membership_change();
