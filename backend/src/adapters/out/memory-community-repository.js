import { Community } from '../../domain/community.js';

export class MemoryCommunityRepository {
  #communities;

  constructor(communities = []) {
    this.#communities = communities.map((community) => new Community(community));
  }

  async list() {
    return this.#communities.map((community) => new Community(community));
  }
}
