import { createHttpServer } from './adapters/in/http-server.js';
import { MemoryCommunityRepository } from './adapters/out/memory-community-repository.js';
import { makeListCommunities } from './application/list-communities.js';

const port = Number(process.env.PORT ?? 3000);
const listCommunities = makeListCommunities(new MemoryCommunityRepository());
const server = createHttpServer({ listCommunities });

server.listen(port, '127.0.0.1', () => {
  console.log(`Nexora API: http://127.0.0.1:${server.address().port}`);
});
