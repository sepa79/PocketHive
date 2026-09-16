"""Responsibility: check declared data preparation and preservation of client usage constraints.
Must not: execute data operations, infer credentials or resolve alternate SUT sources.
Contract: intake-contract.md and the reviewed data source/usage template sections.
"""
from __future__ import annotations

from .applicability import active_execution_rows
from .errors import IntakeError
from .input_values import absent
from .pointers import escape


def _active_apis(requirements: dict, plan: dict) -> list[tuple[int, dict]]:
    active = {row["apiRef"] for _, row in active_execution_rows(plan)}
    return [(i, api) for i, api in enumerate(requirements["templates"]) if api.get("apiId") in active]


def participating_entities(requirements: dict, plan: dict) -> list[tuple[int, dict]]:
    used = {binding["source"].get("entityRef") for _, api in _active_apis(requirements, plan)
            for binding in api.get("payloadBindings", []) if binding["source"].get("type") == "dataset"}
    return [(i, entity) for i, entity in enumerate(requirements["testData"]["entities"])
            if entity.get("entityId") is not None and entity["entityId"] in used]


def _selected_source(entity: dict, plan: dict) -> tuple[int, dict] | None:
    if absent(plan.get("sutId")):
        return None
    rows = [(i, row) for i, row in enumerate(entity.get("sources", [])) if row.get("sutRef") == plan["sutId"]]
    return rows[0] if len(rows) == 1 else None  # Ambiguity belongs to the reference checker.


def _database(requirements: dict, source: dict) -> tuple[int, dict] | None:
    rows = [(i, row) for i, row in enumerate(requirements.get("databases", []))
            if source.get("databaseRef") is not None and row.get("dbId") == source["databaseRef"]]
    return rows[0] if len(rows) == 1 else None


def requires_secrets(requirements: dict, plan: dict) -> bool:
    """Whether declared participating APIs/data need this intake's secret-injection mechanism.

    Redis authentication remains owned by its opaque connectionRef; transport alone
    does not establish a credential requirement.
    """
    if any(api.get("authorization", {}).get("type") not in (None, "none") for _, api in _active_apis(requirements, plan)):
        return True
    for _, entity in participating_entities(requirements, plan):
        selected = _selected_source(entity, plan)
        if selected is not None and selected[1].get("type") == "database-export":
            database = _database(requirements, selected[1])
            if database is not None and not absent(database[1].get("credentialRef")):
                return True
    return False


def check_data(docs: dict) -> tuple[list[dict], list[dict]]:
    gaps: list[dict] = []
    errors: list[dict] = []
    requirements, plan = docs["requirements"], docs["plan"]

    def issue(collection: list, code: str, role: str, pointer: str, message: str) -> None:
        collection.append(IntakeError(code, message, role, pointer).issue)

    def need(row: dict, field: str, role: str, path: str) -> None:
        if absent(row.get(field)):
            issue(gaps, "DATA_SETTING", role, path + "/" + field, "Supply this explicitly selected data/binding setting.")

    for api_index, api in _active_apis(requirements, plan):
        for index, binding in enumerate(api.get("payloadBindings", [])):
            path = f"/templates/{api_index}/payloadBindings/{index}"
            for field in ("location", "path"):
                need(binding, field, "requirements", path)
            source = binding["source"]
            need(source, "type", "requirements", path + "/source")
            if source.get("type") == "dataset":
                for field in ("entityRef", "column"):
                    need(source, field, "requirements", path + "/source")
            elif source.get("type") == "constant" and "value" not in source:
                issue(gaps, "DATA_SETTING", "requirements", path + "/source/value", "Supply the explicit typed constant; an explicit null constant is valid.")
            elif source.get("type") == "generated":
                for field in ("generator", "scope"):
                    need(source, field, "requirements", path + "/source")
            elif source.get("type") == "correlation":
                need(source, "correlationRef", "requirements", path + "/source")
                if absent(plan["executionModel"].get("relationship")):
                    issue(gaps, "CORRELATION_SETTING", "plan", "/executionModel/relationship",
                          "Declare the sequential plan for this prior-step binding.")
                if not any(step.get("apiRef") == api.get("apiId") for step in plan["executionModel"]["sequence"]):
                    issue(gaps, "CORRELATION_SETTING", "plan", "/executionModel/sequence",
                          "Declare the exact destination occurrences for this prior-step binding.")

    for entity_index, entity in participating_entities(requirements, plan):
        base = f"/testData/entities/{entity_index}"
        for index, column in enumerate(entity.get("columns", [])):
            for field in ("name", "type"):
                need(column, field, "requirements", f"{base}/columns/{index}")
        selected = _selected_source(entity, plan)
        if selected is None:
            if not absent(plan.get("sutId")):
                issue(gaps, "DATA_SOURCE", "requirements", base + "/sources", "Identify one declared source for the selected SUT; no other environment is substituted.")
            continue
        source_index, source = selected
        source_path = f"{base}/sources/{source_index}"
        need(source, "type", "requirements", source_path)
        kind = source.get("type")
        fields = {"database-export": ("databaseRef", "queryRef"), "csv": ("location", "delimiter", "hasHeader"),
                  "redis": ("location", "connectionRef")}.get(kind, ())
        for field in fields:
            need(source, field, "requirements", source_path)
        if kind == "generated":
            rules = source.get("generatedColumns", {})
            for column in entity.get("columns", []):
                name = column.get("name")
                if absent(name):
                    continue
                rule = rules.get(name)
                if rule is None:
                    issue(gaps, "DATA_GENERATOR", "requirements", source_path + "/generatedColumns", "Declare a generation rule for each supplied column.")
                    continue
                rule_path = source_path + "/generatedColumns/" + escape(name)
                need(rule, "type", "requirements", rule_path)
                if rule.get("type") == "sequence":
                    need(rule, "start", "requirements", rule_path)
        elif kind == "database-export":
            database = _database(requirements, source)
            if database is not None:
                db_index, db = database
                for field in ("engine", "host", "port", "databaseName", "credentialRef"):
                    need(db, field, "requirements", f"/databases/{db_index}")
                credential = db.get("credentialRef")
                if not absent(credential) and not any(row.get("credentialRef") == credential for row in plan["secretInjection"]["bindings"]):
                    issue(gaps, "DATA_CREDENTIAL", "plan", "/secretInjection/bindings", "Resolve the selected export's declared credential through an explicit injection binding.")
                queries = [(i, row) for i, row in enumerate(db.get("sqlQueries", []))
                           if not absent(source.get("queryRef")) and row.get("queryId") == source["queryRef"]]
                if len(queries) == 1:
                    query_index, query = queries[0]
                    need(query, "sql", "requirements", f"/databases/{db_index}/sqlQueries/{query_index}")
        for index, prepared in enumerate(plan.get("dataPreparation", [])):
            if prepared.get("entityRef") != entity["entityId"]:
                continue
            path = f"/dataPreparation/{index}"
            count, required = prepared.get("rowCount"), entity.get("requiredCount")
            if count is not None and required is not None and count < required:
                issue(errors, "DATA_COUNT", "plan", path + "/rowCount", "Prepared row count cannot undershoot the explicit required count.")
            usage = entity.get("usage", {})
            overrides = prepared.get("usageOverride", {})
            if usage.get("reuseAllowed") is False and overrides.get("reuseAllowed") is True:
                issue(errors, "DATA_USAGE_WEAKENED", "plan", path + "/usageOverride/reuseAllowed", "A plan cannot permit reuse forbidden by the client data constraint.")
            limit, override = usage.get("maxConcurrentRequestsPerRecord"), overrides.get("maxConcurrentRequestsPerRecord")
            if limit is not None and override is not None and override > limit:
                issue(errors, "DATA_USAGE_WEAKENED", "plan", path + "/usageOverride/maxConcurrentRequestsPerRecord", "A plan cannot increase the client's per-record concurrency limit.")
            exhaustion, override_exhaustion = usage.get("onExhaustion"), overrides.get("onExhaustion")
            if not absent(exhaustion) and not absent(override_exhaustion) and exhaustion != override_exhaustion:
                issue(errors, "DATA_EXHAUSTION", "plan", path + "/usageOverride/onExhaustion", "No alternate exhaustion-policy ordering is declared; preserve the required policy or explicitly revise the requirement.")
    return gaps, errors
