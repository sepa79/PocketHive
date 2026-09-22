"""Responsibility: select explicitly participating APIs and the named SUT.
Must not: infer missing modes or choose an alternate SUT. Contract: intake-contract.md.
"""
PARTICIPATING_MODES = frozenset(("load", "readiness-only", "auth-prerequisite"))


def active_execution_rows(plan: dict):
    for index, row in enumerate(plan.get("apiExecution", [])):
        if row.get("mode") in PARTICIPATING_MODES and row.get("apiRef") is not None:
            yield index, row


def active_apis(requirements: dict, plan: dict) -> list[tuple[int, dict]]:
    active = {row["apiRef"] for _, row in active_execution_rows(plan)}
    return [(i, api) for i, api in enumerate(requirements.get("templates", [])) if api.get("apiId") in active]


def selected_sut(requirements: dict, plan: dict) -> dict | None:
    identity = plan.get("sutId")
    return next((sut for sut in requirements.get("suts", []) if identity is not None and sut.get("sutId") == identity), None)
