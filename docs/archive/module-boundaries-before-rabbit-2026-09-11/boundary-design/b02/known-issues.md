# B02 known issues explicitly deferred by the user

## SEL-R1 — stale Redis source during STOP/update/START

Status: **open, deferred by human decision on 2026-09-08**; does not block continuing
the boundary plan. Changing the dataset list is not a normal operation in the user's
workflow. Return to this issue when revisiting runtime list changes.

An in-progress tick can keep popping the previous Redis list after a disabled worker's
list change and re-enable. The HIGH severity and reproduction remain in the
[separate review](redis-selection-review-2026-09-08.md#sel-r1--high-a-resumed-tick-keeps-consuming-the-previous-list).
This decision postpones the fix; it does not mark the defect fixed, authorize another
source-mode change, or accept all of B02.
