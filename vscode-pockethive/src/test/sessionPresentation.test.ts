import assert from 'node:assert/strict';
import test from 'node:test';

import { SESSION_ACTIVITIES, sessionPresentation } from '../webview/sessionPresentation';

test('session presentation keeps renewal inside the authenticated shell and exposes explicit account actions', () => {
  assert.deepEqual(sessionPresentation(SESSION_ACTIVITIES.ACTIVE), {
    status: 'Connected', message: 'Secure session active', canUseWorkspace: true,
    canSignIn: false, canSignOut: true,
  });
  assert.deepEqual(sessionPresentation(SESSION_ACTIVITIES.RESTORING), {
    status: 'Connecting', message: 'Restoring the secure session', canUseWorkspace: false,
    canSignIn: false, canSignOut: false,
  });
  assert.deepEqual(sessionPresentation(SESSION_ACTIVITIES.SIGNING_IN), {
    status: 'Connecting', message: 'Complete sign-in in your browser', canUseWorkspace: false,
    canSignIn: false, canSignOut: false,
  });
  assert.deepEqual(sessionPresentation(SESSION_ACTIVITIES.SIGNING_OUT), {
    status: 'Signing out', message: 'Closing the secure session', canUseWorkspace: false,
    canSignIn: false, canSignOut: false,
  });
  assert.deepEqual(sessionPresentation(SESSION_ACTIVITIES.NEEDS_SIGN_IN), {
    status: 'Needs sign-in', message: 'Sign in again to reconnect this environment', canUseWorkspace: false,
    canSignIn: true, canSignOut: false,
  });
  assert.deepEqual(sessionPresentation(SESSION_ACTIVITIES.UNAVAILABLE), {
    status: 'Unavailable', message: 'The environment connection is unavailable', canUseWorkspace: false,
    canSignIn: false, canSignOut: true,
  });
});
