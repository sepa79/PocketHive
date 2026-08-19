export const SESSION_ACTIVITIES = Object.freeze({
  ACTIVE: 'ACTIVE',
  RESTORING: 'RESTORING',
  SIGNING_IN: 'SIGNING_IN',
  SIGNING_OUT: 'SIGNING_OUT',
  NEEDS_SIGN_IN: 'NEEDS_SIGN_IN',
  UNAVAILABLE: 'UNAVAILABLE',
} as const);

export type SessionActivity = typeof SESSION_ACTIVITIES[keyof typeof SESSION_ACTIVITIES];

export interface SessionPresentation {
  readonly status: 'Connected' | 'Connecting' | 'Needs sign-in' | 'Signing out' | 'Unavailable';
  readonly message: string;
  readonly canUseWorkspace: boolean;
  readonly canSignIn: boolean;
  readonly canSignOut: boolean;
}

export function sessionPresentation(activity: SessionActivity): SessionPresentation {
  switch (activity) {
    case SESSION_ACTIVITIES.ACTIVE:
      return {
        status: 'Connected', message: 'Secure session active', canUseWorkspace: true,
        canSignIn: false, canSignOut: true,
      };
    case SESSION_ACTIVITIES.RESTORING:
      return {
        status: 'Connecting', message: 'Restoring the secure session', canUseWorkspace: false,
        canSignIn: false, canSignOut: false,
      };
    case SESSION_ACTIVITIES.SIGNING_IN:
      return {
        status: 'Connecting', message: 'Complete sign-in in your browser', canUseWorkspace: false,
        canSignIn: false, canSignOut: false,
      };
    case SESSION_ACTIVITIES.SIGNING_OUT:
      return {
        status: 'Signing out', message: 'Closing the secure session', canUseWorkspace: false,
        canSignIn: false, canSignOut: false,
      };
    case SESSION_ACTIVITIES.NEEDS_SIGN_IN:
      return {
        status: 'Needs sign-in', message: 'Sign in again to reconnect this environment', canUseWorkspace: false,
        canSignIn: true, canSignOut: false,
      };
    case SESSION_ACTIVITIES.UNAVAILABLE:
      return {
        status: 'Unavailable', message: 'The environment connection is unavailable', canUseWorkspace: false,
        canSignIn: false, canSignOut: true,
      };
  }
}
