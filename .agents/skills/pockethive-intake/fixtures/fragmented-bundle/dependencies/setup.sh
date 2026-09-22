#!/bin/sh
# Qualification canary: the inspector must never execute this file.
test -n "${INTAKE_EXECUTION_MARKER:-}" && printf 'incorrectly executed\n' > "$INTAKE_EXECUTION_MARKER"
