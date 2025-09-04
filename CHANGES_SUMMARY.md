# Files Modified for Message Limit Control Feature

## Modified Files:
1. `ui/src/store.ts` - Added messageLimit state and validation
2. `ui/src/lib/logs.ts` - Updated to use dynamic limit from store  
3. `ui/src/pages/Buzz.tsx` - Added limit control input in tab bar

## New Files:
1. `ui/src/store.test.ts` - Unit tests for message limit functionality

## Git Commands to Execute:
```bash
git checkout -b feat/buzz-message-limit-control
git add ui/src/store.ts ui/src/lib/logs.ts ui/src/pages/Buzz.tsx ui/src/store.test.ts
git commit -m "feat(ui): add configurable message limit control to Buzz view

- Add messageLimit state to store with 10-500 range validation
- Replace hardcoded MAX_LOGS with dynamic limit from store  
- Add input control in Buzz tab bar for real-time adjustment
- Include unit tests for limit validation logic"
git push origin feat/buzz-message-limit-control
```

## Feature Details:
- **Range**: 10-500 messages (enforced in store)
- **Default**: 100 messages
- **UI**: Small input field in Buzz tab bar (right-aligned)
- **Behavior**: Real-time updates, immediate effect on log storage
- **Validation**: Client-side min/max enforcement with visual feedback