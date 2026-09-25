export type AccessObservation<T> =
  | { status: 'idle' | 'loading'; value: null; error: null }
  | { status: 'ready'; value: T; error: null }
  | { status: 'error'; value: null; error: string }
