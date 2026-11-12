import { api, APIError } from './api.js';
import { entities } from './generated/catalog';
import type { Row } from './generated/api/models/Row.js';

export type RecordRef = { table: string; id: string };
export type RelationKind = 'current' | 'saved_reference';
export type RelationNode = RecordRef & {
  status: 'current' | 'deleted' | 'unavailable' | 'forbidden';
  row?: Row;
  savedTitle?: string;
};
export type RelationEdge = {
  source: RecordRef;
  target: RecordRef;
  fields: string[];
  kind: RelationKind;
  savedTitle?: string;
};
type Definition = {
  source: string;
  field: string;
  target?: string;
  typeField?: string;
  types?: Record<string, string>;
  array?: boolean;
  kind: RelationKind;
  titleField?: string;
};
type SearchTask = { table: string; cursor?: string; seen: string[] };
export type RelationProgress = { outgoing: RelationEdge[]; incoming: SearchTask[] };
export type RelationPage = {
  nodes: RelationNode[];
  edges: RelationEdge[];
  progress: RelationProgress;
};

export const RELATION_NODE_LIMIT = 60;
export const RELATION_DEPTH_LIMIT = 3;
const PAGE_SIZE = 20;
const REQUEST_LIMIT = 6;

// 同步依赖由契约生成；以下额外引用只用于查阅，不改变同步依赖和删除规则。
const definitions: Definition[] = entities.flatMap((entity) =>
  (entity.dependencies as readonly { field: string; table: string; array?: boolean }[]).map(
    (dependency) => ({
      source: entity.table,
      field: dependency.field,
      target: dependency.table,
      array: dependency.array,
      kind: 'current' as const,
    }),
  ),
);
definitions.push(
  { source: 'food_entries', field: 'bill_ids_json', target: 'bills', array: true, kind: 'current' },
  {
    source: 'decision_choice_records',
    field: 'collection_id',
    target: 'decision_collections',
    kind: 'saved_reference',
    titleField: 'collection_title',
  },
  {
    source: 'decision_choice_records',
    field: 'option_id',
    target: 'decision_options',
    kind: 'saved_reference',
    titleField: 'option_title',
  },
  {
    source: 'diary_links',
    field: 'source_id',
    typeField: 'source_type',
    types: {
      track: 'tracks',
      bill: 'bills',
      reminder: 'reminders',
      anniversary: 'anniversaries',
      storedItem: 'stored_items',
      decisionChoice: 'decision_choice_records',
    },
    kind: 'saved_reference',
    titleField: 'source_title',
  },
  {
    source: 'media_attachments',
    field: 'owner_id',
    typeField: 'owner_type',
    types: { diary: 'diary_entries', foodEntry: 'food_entries' },
    kind: 'current',
  },
);

export const recordKey = (ref: RecordRef) => JSON.stringify([ref.table, ref.id]);
export const edgeKey = (edge: RelationEdge) =>
  JSON.stringify([recordKey(edge.source), recordKey(edge.target), edge.kind]);

function ids(value: unknown, array: boolean): string[] {
  if (!array) return typeof value === 'string' && value.trim() ? [value] : [];
  if (typeof value !== 'string') return [];
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed)
      ? [...new Set(parsed.filter((id): id is string => typeof id === 'string' && !!id.trim()))]
      : [];
  } catch {
    return [];
  }
}

export function recordReferences(row: Row): RelationEdge[] {
  const edges = new Map<string, RelationEdge>();
  for (const definition of definitions.filter((definition) => definition.source === row.table)) {
    const type = row.snapshot?.[definition.typeField ?? ''];
    const table =
      definition.target ??
      (typeof type === 'string' && definition.types && Object.hasOwn(definition.types, type)
        ? definition.types[type]
        : '');
    if (!table) continue;
    const savedTitle = row.snapshot?.[definition.titleField ?? ''];
    for (const id of ids(row.snapshot?.[definition.field], !!definition.array)) {
      const edge: RelationEdge = {
        source: { table: row.table, id: row.id },
        target: { table, id },
        fields: [definition.field],
        kind: definition.kind,
        savedTitle: typeof savedTitle === 'string' && savedTitle ? savedTitle : undefined,
      };
      const previous = edges.get(edgeKey(edge));
      if (previous) previous.fields.push(definition.field);
      else edges.set(edgeKey(edge), edge);
    }
  }
  return [...edges.values()];
}

export function relationSearchTables(table: string): string[] {
  return [
    ...new Set(
      definitions
        .filter(
          (definition) =>
            definition.target === table || Object.values(definition.types ?? {}).includes(table),
        )
        .map((definition) => definition.source),
    ),
  ];
}

export function relationProgress(row: Row): RelationProgress {
  return {
    outgoing: recordReferences(row),
    incoming: relationSearchTables(row.table).map((table) => ({ table, seen: [] })),
  };
}

export function relationComplete(progress: RelationProgress): boolean {
  return !progress.outgoing.length && !progress.incoming.length;
}

export function relationNode(row: Row): RelationNode {
  return { table: row.table, id: row.id, row, status: row.deletedAtMs ? 'deleted' : 'current' };
}

// 搜索只缩小候选范围；必须再核对类型与完整 ID，标题、日期、快照内相似内容都不构成关联。
export async function loadRelationPage(
  row: Row,
  previous: RelationProgress,
  known: ReadonlySet<string>,
  capacity: number,
  signal: AbortSignal,
  client: Pick<typeof api, 'record' | 'records'> = api,
): Promise<RelationPage> {
  // Svelte 状态可能是 Proxy；只复制会变动的分页容器，不克隆或改写业务快照。
  const progress: RelationProgress = {
    outgoing: [...previous.outgoing],
    incoming: previous.incoming.map((task) => ({ ...task, seen: [...task.seen] })),
  };
  const nodes = new Map<string, RelationNode>();
  const edges: RelationEdge[] = [];
  const budget = Math.min(PAGE_SIZE, Math.max(0, capacity));
  let requests = 0;
  const hasNode = (ref: RecordRef) => known.has(recordKey(ref)) || nodes.has(recordKey(ref));
  while (progress.outgoing.length && requests < REQUEST_LIMIT) {
    signal.throwIfAborted();
    const edge = progress.outgoing[0];
    if (!hasNode(edge.target)) {
      if (nodes.size >= budget) break;
      requests++;
      let node: RelationNode;
      try {
        const target = await client.record(edge.target.table, edge.target.id, signal);
        if (recordKey(target) !== recordKey(edge.target))
          throw new Error('Invalid record response');
        node = relationNode(target);
      } catch (error) {
        if (!(error instanceof APIError) || ![403, 404].includes(error.status)) throw error;
        node = { ...edge.target, status: error.status === 403 ? 'forbidden' : 'unavailable' };
      }
      node.savedTitle = edge.savedTitle;
      nodes.set(recordKey(node), node);
    }
    edges.push(edge);
    progress.outgoing.shift();
  }
  while (progress.incoming.length && nodes.size < budget && requests < REQUEST_LIMIT) {
    signal.throwIfAborted();
    const task = progress.incoming[0];
    const page = await client.records(
      task.table,
      {
        q: row.id,
        cursor: task.cursor,
        limit: Math.min(PAGE_SIZE, budget - nodes.size),
      },
      signal,
    );
    requests++;
    for (const candidate of page.rows) {
      if (candidate.table !== task.table) throw new Error('Invalid record response');
      if (candidate.deletedAtMs) continue;
      const matching = recordReferences(candidate).filter(
        (edge) => recordKey(edge.target) === recordKey(row),
      );
      if (!matching.length) continue;
      if (!hasNode(candidate)) nodes.set(recordKey(candidate), relationNode(candidate));
      edges.push(...matching);
    }
    progress.incoming.shift();
    if (page.more) {
      if (
        !page.nextCursor ||
        page.nextCursor === task.cursor ||
        task.seen.includes(page.nextCursor)
      ) {
        throw new Error('Invalid pagination cursor');
      }
      // 分页表轮转到队尾，避免大量轨迹点挡住其他类别。
      progress.incoming.push({
        ...task,
        cursor: page.nextCursor,
        seen: [...task.seen, page.nextCursor],
      });
    }
  }
  signal.throwIfAborted();
  return { nodes: [...nodes.values()], edges, progress };
}

export function relationDepths(root: RecordRef, edges: RelationEdge[]): Map<string, number> {
  const depths = new Map([[recordKey(root), 0]]);
  const queue = [recordKey(root)];
  for (const key of queue) {
    for (const edge of edges) {
      const a = recordKey(edge.source),
        b = recordKey(edge.target);
      const next = a === key ? b : b === key ? a : undefined;
      if (next && !depths.has(next)) {
        depths.set(next, (depths.get(key) ?? 0) + 1);
        queue.push(next);
      }
    }
  }
  return depths;
}
