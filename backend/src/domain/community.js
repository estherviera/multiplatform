export class Community {
  constructor({ id, name }) {
    if (!id || typeof name !== 'string' || !name.trim()) {
      throw new TypeError('Community requires an id and a name');
    }

    this.id = id;
    this.name = name.trim();
  }
}
