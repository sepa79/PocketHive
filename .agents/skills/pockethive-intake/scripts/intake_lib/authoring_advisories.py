"""Responsibility: report read-only review notices from explicit intake facts.
Must not: infer request contracts, time semantics or targets, or author questions.
Local contract: intake-contract.md, Authoring review notices.
Contract: RESP-INTAKE-REVIEW-VIEWS — docs/architecture/intake-runtime.md#resp-intake-review-views.
"""
from __future__ import annotations

from datetime import date
import re

from .applicability import active_apis, active_execution_rows, selected_sut
from .errors import IntakeError
from .input_values import absent
from .pointers import leaves


DATE_ONLY = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")


def _calendar_date(value: object) -> bool:
    if not isinstance(value, str) or DATE_ONLY.fullmatch(value) is None:
        return False
    try:
        date.fromisoformat(value)
    except ValueError:
        return False
    return True


def authoring_advisories(docs: dict) -> list[dict]:
    """Inspect schema-checked canonical plain values without mutating them."""
    requirements, plan = docs["requirements"], docs["plan"]
    warnings: list[dict] = []

    def notice(code: str, pointer: str, message: str) -> None:
        warnings.append(IntakeError(code, message, "requirements", pointer).issue)

    participating = active_apis(requirements, plan)
    for api_index, api in participating:
        base = f"/templates/{api_index}"
        bindings = api.get("payloadBindings", [])
        sample = api.get("requestSample")
        if isinstance(sample, (dict, list)) and sample and not any(binding.get("location") == "body" for binding in bindings):
            notice("PAYLOAD_BINDINGS_REVIEW", base + "/payloadBindings",
                   "A request sample is present without body bindings. Review the intended body against the API contract; samples do not define complete bindings.")
        for binding_index, binding in enumerate(bindings):
            if "source" not in binding:
                continue
            source = binding["source"]
            if source.get("type") == "constant" and "value" in source:
                for pointer, value in leaves(source["value"], f"{base}/payloadBindings/{binding_index}/source/value"):
                    if _calendar_date(value):
                        notice("DATE_CONSTANT_REVIEW", pointer,
                               "Review this date constant's intended time semantics and validity for the planned run; no expiry or replacement is inferred.")

    selected = selected_sut(requirements, plan)
    if selected is not None and absent(requirements.get("productionUsage", {}).get("available")):
        participating_ids = {api["apiId"] for _, api in participating}
        load_ids = {row["apiRef"] for _, row in active_execution_rows(plan)
                    if row["mode"] == "load" and row["apiRef"] in participating_ids}
        for index, kpi in enumerate(requirements.get("kpis", [])):
            if kpi.get("sutRef") == selected["sutId"] and kpi.get("apiRef") in load_ids and absent(kpi.get("targetTps")):
                notice("PRODUCTION_CONTEXT_REVIEW", f"/kpis/{index}/targetTps",
                       "Production evidence availability and this load API's TPS target are unknown. Review the target's basis and scope; production evidence remains optional.")
    return warnings
