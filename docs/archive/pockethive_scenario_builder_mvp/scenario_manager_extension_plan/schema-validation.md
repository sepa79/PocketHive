
# Scenario JSON — validation (current behavior)

- Enforces `id` and `name` as non-blank.
- Validates `template` and `trafficPolicy` via bean validation when present.
- Treats `plan` as an opaque JSON map (no schema validation here).
- Ignores unknown fields (`@JsonIgnoreProperties(ignoreUnknown = true)`).
- No `runConfig/runPrefix` validation in Scenario Manager (apply/run flow is not implemented here).
