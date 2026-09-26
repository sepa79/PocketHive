# B02 known issues explicitly deferred by the user

## SEL-R1 — stale Redis source during STOP/update/START

Status: **fixed on 2026-09-26** after explicit user authorization. Redis intake now
invalidates batches on disable/stop and serializes pop admission with state updates.
Redis connection/read/close IO does not hold the state monitor; a late connection
from an invalidated batch is closed.
Regression tests cover list replacement, STOP with/without immediate START during
dispatch, and interruption between multi-source reads. The original finding below
is historical evidence, not an open deferral.

An in-progress tick can keep popping the previous Redis list after a disabled worker's
list change and re-enable. The HIGH severity and reproduction remain in the
[separate review](redis-selection-review-2026-09-08.md#sel-r1--high-a-resumed-tick-keeps-consuming-the-previous-list).
The original deferral did not accept all of B02; this fix is scoped to intake lifecycle.
