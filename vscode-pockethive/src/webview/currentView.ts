export class CurrentView<T> {
  private current?: T;

  attach(view: T): void {
    this.current = view;
  }

  detach(view: T): void {
    if (this.current === view) this.current = undefined;
  }

  value(): T | undefined {
    return this.current;
  }
}
