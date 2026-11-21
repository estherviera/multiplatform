# Backend de Nexora

Servicio HTTP independiente de la app Android que está en `app/`. Requiere Node.js 20 o superior y usa la biblioteca estándar de Node.

## Arranque local

Desde la raíz del repositorio:

```powershell
cd backend
npm start
```

| Ruta | Respuesta inicial |
| --- | --- |
| `GET http://127.0.0.1:3000/health` | `{"status":"ok"}` |
| `GET http://127.0.0.1:3000/api/communities` | `{"items":[]}` |

El servidor escucha en la interfaz local. La variable de entorno `PORT` permite cambiar el puerto.

## Arquitectura hexagonal

- `src/domain/` contiene la entidad `Community`.
- `src/application/` contiene el caso de uso para listar comunidades y el contrato `CommunityRepository`.
- `src/adapters/in/` traduce solicitudes HTTP a llamadas a los casos de uso.
- `src/adapters/out/` implementa el contrato con un repositorio en memoria. Arranca vacío.
- `src/main.js` conecta los adaptadores con el caso de uso y arranca el servicio.

Un adaptador de base de datos podrá implementar el mismo contrato para listar las comunidades guardadas.
