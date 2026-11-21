/**
 * @typedef {{ list(): Promise<import('../domain/community.js').Community[]> }} CommunityRepository
 */

/** @param {CommunityRepository} repository */
export function makeListCommunities(repository) {
  return () => repository.list();
}
