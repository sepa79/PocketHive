# PR: Add message limit control to Buzz view

## Summary
- Add configurable message limit control to Buzz view with range 10-500, default 100
- Replace hardcoded MAX_LOGS with dynamic limit from store
- Add input control in Buzz tab bar for real-time limit adjustment

## Changes Made

### `ui/src/store.ts`
- Added `messageLimit: number` state (default: 100)
- Added `setMessageLimit` function with min/max validation (10-500)

### `ui/src/lib/logs.ts`
- Imported store to access dynamic message limit
- Replaced hardcoded `MAX_LOGS = 200` with `useUIStore.getState().messageLimit`
- Updated `addLog` function to use configurable limit

### `ui/src/pages/Buzz.tsx`
- Added message limit input control in tab bar
- Added real-time validation and state management
- Positioned control on right side with "Limit:" label

### `ui/src/store.test.ts` (new)
- Added unit tests for message limit functionality
- Tests default value, min/max enforcement, and valid range acceptance

## Checklist
- [x] Tests added or updated
- [x] Architecture and policy respected
- [x] Documentation updated
- [x] Conventional Commit message

## Commit Message
```
feat(ui): add configurable message limit control to Buzz view

- Add messageLimit state to store with 10-500 range validation
- Replace hardcoded MAX_LOGS with dynamic limit from store
- Add input control in Buzz tab bar for real-time adjustment
- Include unit tests for limit validation logic

Closes: #[issue-number]
```