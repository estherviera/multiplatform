// Run with Node on Windows after stopping the three Nexora containers.
const http = require('node:http');
const socketPath = '\\\\.\\pipe\\dockerDesktopLinuxEngine';

function request(method, path, body) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body);
    const headers = data ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } : {};
    const req = http.request({ socketPath, method, path, headers }, (res) => {
      let result = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { result += chunk; });
      res.on('end', () => {
        if (res.statusCode >= 300) reject(new Error(`${method} ${path}: HTTP ${res.statusCode}`));
        else resolve(result ? JSON.parse(result) : undefined);
      });
    });
    req.on('error', reject);
    req.end(data);
  });
}

async function main() {
  for (const [service, port, hostPort] of [['db', '5432/tcp', '55422'], ['auth', '9999/tcp', '55421']]) {
    const name = `supabase_${service}_nexora_prephase`;
    const original = await request('GET', `/containers/${name}/json`);
    const { Id, Config, HostConfig, State } = original;
    if (Config.Labels['com.supabase.cli.project'] !== 'nexora_prephase' ||
        HostConfig.NetworkMode !== 'nexora_prephase_loopback' || State.Running ||
        Object.keys(HostConfig.PortBindings ?? {}).length > 1 ||
        (service === 'db' && (HostConfig.PortBindings[port]?.length !== 1 ||
          HostConfig.PortBindings[port][0].HostPort !== hostPort))) {
      throw new Error(`Configuración inesperada para ${name}`);
    }
    if (HostConfig.PortBindings?.[port]?.[0]?.HostIp === '127.0.0.1') continue;
    HostConfig.PortBindings = { [port]: [{ HostIp: '127.0.0.1', HostPort: hostPort }] };
    if (service === 'auth') {
      const externalUrl = `http://127.0.0.1:${hostPort}`;
      const settings = {
        API_EXTERNAL_URL: externalUrl,
        GOTRUE_JWT_ISSUER: externalUrl,
      };
      Config.Env = Config.Env.map((line) => {
        const key = line.split('=', 1)[0];
        if (key.startsWith('GOTRUE_MAILER_URLPATHS_')) return line.replace('=/auth/v1/', '=/');
        return Object.hasOwn(settings, key) ? `${key}=${settings[key]}` : line;
      });
    }
    const backupName = `${name}_sin_loopback`;
    await request('POST', `/containers/${Id}/rename?name=${backupName}`);
    try {
      await request('POST', `/containers/create?name=${name}`, { ...Config, HostConfig });
    } catch (error) {
      await request('POST', `/containers/${Id}/rename?name=${name}`);
      throw error;
    }
    await request('DELETE', `/containers/${Id}?v=false`);
    console.log(`${name}: 127.0.0.1:${hostPort}`);
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
