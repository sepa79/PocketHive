import { randomUUID } from 'node:crypto';

import { ConnectionContractError } from '../connection/contracts';

const OPTIONAL_SUMMARY_STRING_FIELDS = Object.freeze([
  'severity',
  'type',
  'swarmId',
  'runId',
  'origin',
  'direction',
  'routingKey',
  'summary',
] as const);

type OptionalSummaryStringField = typeof OPTIONAL_SUMMARY_STRING_FIELDS[number];
type OwnerEvent = Readonly<Record<string, unknown>>;

export interface CompanionEventSummary extends Partial<Record<OptionalSummaryStringField, string>> {
  readonly detailId: string;
  readonly eventId?: string | number;
  readonly timestamp: string;
  readonly kind: string;
}

export interface CompanionEventPage {
  readonly items: readonly CompanionEventSummary[];
  readonly nextCursor: unknown;
  readonly hasMore: boolean;
}

export class EventPagePresentation {
  private readonly records = new Map<string, OwnerEvent>();

  constructor(private readonly createId: () => string = randomUUID) {}

  replace(value: unknown): CompanionEventPage {
    this.clear();
    try {
      const page = requiredRecord(value);
      if (!Array.isArray(page.items)
          || typeof page.hasMore !== 'boolean'
          || !Object.hasOwn(page, 'nextCursor')) {
        return invalidPage();
      }
      const items = page.items.map(item => this.projectEvent(item));
      return { items, nextCursor: projectCursor(page.nextCursor), hasMore: page.hasMore };
    } catch (error) {
      this.clear();
      throw error;
    }
  }

  require(detailId: string): OwnerEvent {
    const event = this.records.get(detailId);
    if (!event) {
      throw new ConnectionContractError(
        'EVENT_DETAIL_NOT_AVAILABLE',
        'The selected event is no longer available. Refresh the current page.',
      );
    }
    return event;
  }

  clear(): void {
    this.records.clear();
  }

  private projectEvent(value: unknown): CompanionEventSummary {
    const event = requiredRecord(value);
    const detailId = requiredString(this.createId());
    if (this.records.has(detailId)) {
      throw new ConnectionContractError('EVENT_DETAIL_ID_COLLISION', 'Event detail ID collision');
    }
    const summary: Record<string, string | number> = {
      detailId,
      timestamp: requiredString(event.timestamp),
      kind: requiredString(event.kind),
    };
    const eventId = optionalEventId(event.eventId);
    if (eventId !== undefined) summary.eventId = eventId;
    for (const field of OPTIONAL_SUMMARY_STRING_FIELDS) {
      const item = event[field];
      if (item === undefined || item === null) continue;
      if (typeof item !== 'string' || !item.trim()) return invalidPage();
      summary[field] = item;
    }
    this.records.set(detailId, event);
    return summary as unknown as CompanionEventSummary;
  }
}

function projectCursor(value: unknown): null | { readonly ts: string; readonly id: number } {
  if (value === null) return null;
  const cursor = requiredRecord(value);
  if (Object.keys(cursor).sort().join('|') !== 'id|ts') return invalidPage();
  const id = cursor.id;
  if (!Number.isFinite(id)) return invalidPage();
  return { ts: requiredString(cursor.ts), id: id as number };
}

function optionalEventId(value: unknown): string | number | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value === 'string') return requiredString(value);
  if (!Number.isFinite(value)) return invalidPage();
  return value as number;
}

function requiredRecord(value: unknown): OwnerEvent {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalidPage();
  return value as OwnerEvent;
}

function requiredString(value: unknown): string {
  if (typeof value !== 'string' || !value.trim()) return invalidPage();
  return value;
}

function invalidPage(): never {
  throw new ConnectionContractError(
    'COMPANION_EVENT_PAGE_INVALID',
    'PocketHive returned an invalid event page.',
  );
}
