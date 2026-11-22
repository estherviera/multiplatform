# Arquitectura de Nexora

## Estado observado
La aplicación Android, módulo :app en frontend/android, usa Compose, ViewModels, Room, DataStore y Media3. La API Kotlin/JVM vive en backend, con dominio, aplicación, adaptadores HTTP y SQLite y bootstrap. Mantienen datos independientes. Consulta README.md y backend/README.md para comandos y contrato actual.

## Dirección propuesta
Evolucionar por funcionalidades, manteniendo límites claros entre presentación, reglas de negocio y datos. Conservar los puertos del backend y sus adaptadores. Introducir un cliente remoto y reconciliación de datos cuando se acuerde la integración. Definir fuente de verdad, migración y comportamiento sin conexión antes de conectar datos existentes.

La autenticación y autorización del servidor, chat, moderación y subida y procesamiento de vídeo requieren diseño e implementación. Seleccionar tecnologías según requisitos y registrar decisiones relevantes en docs/decisions.

## Diagnóstico y decisiones pendientes

- NexoraRepository agrupa persistencia, sesión y archivos locales. Extraer responsabilidades gradualmente cuando una funcionalidad lo justifique y preservar sus consumidores.
- Room usa versión 2, no exporta esquemas y permite migración destructiva. Antes de datos reales, acordar migraciones y comprobar actualización desde versiones anteriores.
- El acceso local calcula SHA-256 directamente para contraseñas. Diseñar autenticación de servidor y tratamiento de cuentas existentes antes de su uso real.
- El servidor ofrece comunidades locales sin autorización. Definir identidad, permisos, contratos y despliegue antes de exponerlo.
- Definir identidad y correspondencia entre Empresa local y Community, fuente de verdad y reconciliación antes de conectar cliente y servidor.
- La selección de cliente HTTP, inyección de dependencias y proveedor multimedia sigue pendiente. Evaluar requisitos, costes y alternativas mediante ADR cuando se tome cada decisión.

