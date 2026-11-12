import { it, expect } from 'vitest';
import { filterURL, recordFilters } from './record-filters.js';
it('keeps exact time, empty category and unknown category names through drilldown', () => {
  for (const category of ['', 'custom/中文%_']) {
    const filters = {
      fromMs: 1790000000123,
      toMs: 1790000100456,
      transaction_type: 'expense',
      category,
      exclude_refunded: 'true',
    };
    expect(recordFilters(new URLSearchParams(filterURL(filters)))).toEqual(filters);
  }
});
