export function recordFilters(
  params: Pick<URLSearchParams, 'get' | 'has'>,
): Record<string, string | number | undefined> {
  const filters: Record<string, string | number | undefined> = {};
  if (params.get('q')) filters.q = params.get('q')!;
  for (const key of ['from_ms', 'to_ms']) {
    const value = Number(params.get(key));
    if (Number.isSafeInteger(value) && value > 0)
      filters[key === 'from_ms' ? 'fromMs' : 'toMs'] = value;
  }
  if (['expense', 'income', 'storedValueUse'].includes(params.get('transaction_type') ?? ''))
    filters.transaction_type = params.get('transaction_type')!;
  if (params.has('category')) filters.category = params.get('category')!;
  if (params.get('exclude_refunded') === 'true') filters.exclude_refunded = 'true';
  return filters;
}

export function filterURL(filters: Record<string, string | number | undefined>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value === undefined || (value === '' && key !== 'category')) continue;
    params.set(key === 'fromMs' ? 'from_ms' : key === 'toMs' ? 'to_ms' : key, String(value));
  }
  return params.size ? '?' + params.toString() : '';
}

export function filterDate(value: unknown, end = false): string {
  if (typeof value !== 'number') return '';
  const date = new Date(value - (end ? 1 : 0));
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
