export const TAB_DATA_REFRESH_INTERVAL_MS = 15_000;

export interface VisibleAutoRefreshTimerPort {
  schedule(delayMs: number, action: () => Promise<void>): unknown;
  cancel(handle: unknown): void;
}

export const VISIBLE_AUTO_REFRESH_SYSTEM_TIMERS: VisibleAutoRefreshTimerPort = Object.freeze({
  schedule(delayMs: number, action: () => Promise<void>): unknown {
    const handle = setTimeout(() => { void action().catch(() => undefined); }, delayMs);
    handle.unref();
    return handle;
  },
  cancel(handle: unknown): void {
    clearTimeout(handle as NodeJS.Timeout);
  },
});

export class VisibleAutoRefresh {
  private enabled = false;
  private visible = false;
  private disposed = false;
  private inFlight = false;
  private scheduled?: unknown;

  constructor(
    private readonly action: () => Promise<void>,
    private readonly timers: VisibleAutoRefreshTimerPort = VISIBLE_AUTO_REFRESH_SYSTEM_TIMERS,
  ) {}

  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
    if (enabled) this.schedule(TAB_DATA_REFRESH_INTERVAL_MS);
    else this.cancel();
  }

  setVisible(visible: boolean): void {
    this.visible = visible;
    if (visible) this.schedule(0);
    else this.cancel();
  }

  defer(): void {
    this.cancel();
    this.schedule(TAB_DATA_REFRESH_INTERVAL_MS);
  }

  dispose(): void {
    this.disposed = true;
    this.cancel();
  }

  private schedule(delayMs: number): void {
    if (this.disposed || !this.enabled || !this.visible || this.inFlight || this.scheduled !== undefined) return;
    this.scheduled = this.timers.schedule(delayMs, () => this.run());
  }

  private cancel(): void {
    if (this.scheduled === undefined) return;
    this.timers.cancel(this.scheduled);
    this.scheduled = undefined;
  }

  private async run(): Promise<void> {
    if (this.inFlight || !this.enabled || !this.visible || this.disposed) return;
    this.scheduled = undefined;
    this.inFlight = true;
    try {
      await this.action();
    } finally {
      this.inFlight = false;
      this.schedule(TAB_DATA_REFRESH_INTERVAL_MS);
    }
  }
}
