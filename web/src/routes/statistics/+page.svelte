<script lang="ts">
  import { createQuery } from '@tanstack/svelte-query';
  import { api, APIError } from '#lib/api.js';
  import { t } from '#lib/preferences.svelte.js';
  import { integer, money, timezone, valueText } from '#lib/format.js';
  import { appPath } from '#lib/paths.js';
  import { filterURL } from '#lib/record-filters.js';
  import YearField from '#lib/components/YearField.svelte';
  import PageHeader from '#lib/components/PageHeader.svelte';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  import Freshness from '#lib/components/Freshness.svelte';
  import { Button } from '#lib/components/ui/button/index.js';
  import { ChevronLeft, ChevronRight } from '@lucide/svelte';
  let year = $state(new Date().getFullYear());
  let inputYear = $state(new Date().getFullYear());
  const zone = timezone();
  const query = createQuery(() => ({
    queryKey: ['bill-statistics', year, zone],
    queryFn: ({ signal }) => api.billStatistics(year, zone, signal),
  }));
  const kinds = ['expense', 'income', 'storedValueUse'] as const;
  const amountKeys = {
    expense: 'expenseCents',
    income: 'incomeCents',
    storedValueUse: 'storedValueUseCents',
  } as const;
  const totals = $derived(
    kinds.map((kind) => {
      const current = (query.data?.months ?? []).reduce(
        (sum, month) => sum + (integer(month[amountKeys[kind]]) ?? 0n),
        0n,
      );
      const previous = (query.data?.previousMonths ?? []).reduce(
        (sum, month) => sum + (integer(month[amountKeys[kind]]) ?? 0n),
        0n,
      );
      return { kind, current, previous, difference: current - previous };
    }),
  );
  const maximum = $derived(
    (query.data?.months ?? []).reduce((max, month) => {
      const value = integer(month.expenseCents) ?? 0n;
      return value > max ? value : max;
    }, 0n),
  );
  const billLink = (kind: string, fromMs: number, toMs: number, category?: string) =>
    appPath('/records/bills') +
    filterURL({ transaction_type: kind, fromMs, toMs, category, exclude_refunded: 'true' });
  function selectYear(value: number) {
    if (Number.isInteger(value) && value >= 1971 && value <= 9998) {
      year = value;
      inputYear = value;
    }
  }
</script>

<PageHeader title={t('bill_statistics')} description={t('bill_statistics_description')} back="/" />
<section class="panel filter-panel">
  <form
    class="statistics-controls panel-body"
    onsubmit={(event) => {
      event.preventDefault();
      selectYear(inputYear);
    }}
  >
    <Button
      variant="outline"
      aria-label={t('previous_year')}
      disabled={year <= 1971}
      onclick={() => selectYear(year - 1)}><ChevronLeft size={16} /></Button
    >
    <YearField label={t('year')} bind:value={inputYear} min={1971} max={9998} />
    <Button
      variant="outline"
      aria-label={t('next_year')}
      disabled={year >= 9998}
      onclick={() => selectYear(year + 1)}><ChevronRight size={16} /></Button
    >
    <Button type="submit">{t('apply_date_range')}</Button>
    <p class="filter-hint">{t('timezone', { zone })} · {t('refunds_excluded')}</p>
  </form>
</section>
{#if query.error instanceof APIError && query.error.status === 404}<div
    class="status"
    role="status"
  >
    {t('bill_statistics_unsupported')}
  </div>{:else}<QueryStatus
    pending={query.isPending}
    error={query.error}
    retry={() => query.refetch()}
  />{/if}
{#if query.data}
  <div class="stack">
    <section class="panel">
      <div class="panel-heading"><h2>{t('annual_comparison')}</h2></div>
      <div class="table-wrap">
        <table class="data-table">
          <thead
            ><tr
              ><th>{t('transaction_type')}</th><th>{year}</th><th>{year - 1}</th><th
                >{t('annual_difference')}</th
              ></tr
            ></thead
          >
          <tbody
            >{#each totals as total (total.kind)}<tr>
                <td>{valueText('bills', 'transaction_type', total.kind)}</td>
                <td data-label={String(year)} class="amount-cell"
                  ><a
                    class="text-link"
                    href={billLink(
                      total.kind,
                      query.data.months[0].fromMs,
                      query.data.months[11].toMs,
                    )}>{money(total.current)}</a
                  ></td
                >
                <td data-label={String(year - 1)} class="amount-cell"
                  ><a
                    class="text-link"
                    href={billLink(
                      total.kind,
                      query.data.previousMonths[0].fromMs,
                      query.data.previousMonths[11].toMs,
                    )}>{money(total.previous)}</a
                  ></td
                >
                <td data-label={t('annual_difference')} class="amount-cell"
                  >{total.difference > 0n ? '+' : ''}{money(total.difference)}</td
                >
              </tr>{/each}</tbody
          >
        </table>
      </div>
    </section>
    <section class="panel">
      <div class="panel-heading">
        <h2>{t('monthly_income_expense')}</h2>
        <span class="muted">{t('select_amount_to_view_bills')}</span>
      </div>
      <div class="table-wrap">
        <table class="data-table">
          <thead
            ><tr
              ><th>{t('month')}</th>{#each kinds as kind (kind)}<th
                  >{valueText('bills', 'transaction_type', kind)}</th
                >{/each}</tr
            ></thead
          >
          <tbody
            >{#each query.data.months as month (month.month)}<tr>
                <td class="record-title">{year}-{String(month.month).padStart(2, '0')}</td>
                {#each kinds as kind (kind)}<td
                    data-label={valueText('bills', 'transaction_type', kind)}
                    class="amount-cell"
                  >
                    <a class="text-link" href={billLink(kind, month.fromMs, month.toMs)}
                      >{money(month[amountKeys[kind]])}</a
                    >
                    {#if kind === 'expense'}<span class="monthly-meter" aria-hidden="true"
                        ><span
                          style:width={`${maximum > 0n ? Number(((integer(month.expenseCents) ?? 0n) * 1000n) / maximum) / 10 : 0}%`}
                        ></span></span
                      >{/if}
                  </td>{/each}
              </tr>{/each}</tbody
          >
        </table>
      </div>
    </section>
    <section class="panel">
      <div class="panel-heading"><h2>{t('category_expenses')}</h2></div>
      {#if query.data.categories.length}<div class="table-wrap">
          <table class="data-table">
            <thead
              ><tr
                ><th>{t('category')}</th><th>{t('bill_expenses')}</th><th>{t('total_records')}</th
                ></tr
              ></thead
            >
            <tbody
              >{#each query.data.categories as category (category.category)}<tr>
                  <td
                    ><a
                      class="record-title text-link"
                      href={billLink(
                        'expense',
                        query.data.months[0].fromMs,
                        query.data.months[11].toMs,
                        category.category,
                      )}
                      >{category.category === ''
                        ? t('uncategorized')
                        : valueText('bills', 'category', category.category)}</a
                    ></td
                  >
                  <td data-label={t('bill_expenses')} class="amount-cell"
                    >{money(category.expenseCents)}</td
                  >
                  <td data-label={t('total_records')}>{String(category.count)}</td>
                </tr>{/each}</tbody
            >
          </table>
        </div>{:else}<div class="status">{t('no_expenses_this_year')}</div>{/if}
    </section>
    <div class="page-freshness"><Freshness at={query.dataUpdatedAt} /></div>
  </div>
{/if}
