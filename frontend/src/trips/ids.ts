const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Trip and request IDs are UUIDs; anything else from a URL is never put into an API path. */
export function isUuid(value: string): boolean {
  return UUID.test(value);
}
