import { isLosslessNumber } from 'lossless-json';
import { apiFieldNames } from './api-field-names.generated.js';

const legacyNames = Object.fromEntries(Object.entries(apiFieldNames).map(([a, b]) => [b, a]));
const opaque = new Set([
  'snapshot',
  'metadata',
  'before_value',
  'after_value',
  'contracts',
  'record_counts',
]);

export const apiFieldName = (name: string) => apiFieldNames[name] ?? name;

// Keep language-level model names while the HTTP contract uses snake_case.
export function apiFields(value: unknown, legacy = true): unknown {
  if (Array.isArray(value)) return value.map((item) => apiFields(item, legacy));
  if (!value || typeof value !== 'object' || isLosslessNumber(value)) return value;
  const result: Record<string, unknown> = {};
  for (const [name, item] of Object.entries(value)) {
    const canonical = apiFieldName(name);
    const key = legacy ? (legacyNames[canonical] ?? canonical) : canonical;
    if (Object.hasOwn(result, key)) throw new Error('Duplicate API field');
    result[key] = opaque.has(canonical) ? item : apiFields(item, legacy);
  }
  return result;
}
