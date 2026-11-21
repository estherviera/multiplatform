import { createServer } from 'node:http';

function sendJson(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify(body));
}

export function createHttpServer({ listCommunities }) {
  return createServer(async (request, response) => {
    const path = request.url.split('?')[0];

    if (path !== '/health' && path !== '/api/communities') {
      return sendJson(response, 404, { error: 'not_found' });
    }

    if (request.method !== 'GET') {
      response.setHeader('Allow', 'GET');
      return sendJson(response, 405, { error: 'method_not_allowed' });
    }

    if (path === '/health') {
      return sendJson(response, 200, { status: 'ok' });
    }

    try {
      const communities = await listCommunities();
      const items = communities.map(({ id, name }) => ({ id, name }));
      return sendJson(response, 200, { items });
    } catch (error) {
      console.error('Unable to list communities', error);
      return sendJson(response, 500, { error: 'internal_error' });
    }
  });
}
