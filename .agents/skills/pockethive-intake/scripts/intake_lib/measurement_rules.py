"""Responsibility: check explicit KPI mappings and the evidence consistency of recorded results.
Must not: infer metric meaning, calculate outcomes, approve runs or inspect live systems.
Contract: intake-contract.md#measurement-mappings-and-recorded-results; RESP-INTAKE-RESULT-EVIDENCE.
Architecture: docs/architecture/runtime-responsibilities.md#resp-intake-result-evidence.
"""
from __future__ import annotations

import re

from .applicability import active_execution_rows
from .errors import IntakeError
from .input_values import absent
from .package_context import canonical_hash
from .pointers import resolve


KPI_POINTER = re.compile(r"/kpis/(0|[1-9][0-9]*)/(targetTps|maxResponseTimeMs)")
PLAN_RULE_POINTER = re.compile(r"/acceptanceCriteria/(0|[1-9][0-9]*)/measurableRules/(0|[1-9][0-9]*)")
RESULT_RULE_POINTER = re.compile(r"/criterionResults/(0|[1-9][0-9]*)/ruleResults/(0|[1-9][0-9]*)")
KPI_CONTRACTS = {
    "targetTps": ("successful-transactions-per-second", frozenset({">=", ">"})),
    "maxResponseTimeMs": ("milliseconds", frozenset({"<=", "<"})),
}
EXECUTED_STATUSES = frozenset({"pass", "fail", "inconclusive"})
CLAIMED_OUTCOMES = frozenset({"pass", "fail"})


def _value(document: dict, pointer: str) -> object:
    try:
        return resolve(document, pointer)
    except IntakeError:
        return None


def _present(value: object) -> bool:
    return not absent(value) and value != [] and value != {}


def _number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _rows(document: dict, pointer: str) -> list:
    value = _value(document, pointer)
    return value if isinstance(value, list) else []


def _issue(collection: list, code: str, role: str, pointer: str, message: str) -> None:
    collection.append(IntakeError(code, message, document=role, pointer=pointer).issue)


def _check_kpis(docs: dict, gaps: list, errors: list) -> None:
    requirements, plan = docs["requirements"], docs["plan"]
    active = {row["apiRef"] for _, row in active_execution_rows(plan)}
    selected = plan.get("sutId")
    mappings: dict[str, list[tuple[int, dict]]] = {}
    for index, row in enumerate(_rows(docs["traceability"], "/instance/coverage")):
        rule_pointer = row.get("planRulePointer")
        if _present(rule_pointer) and PLAN_RULE_POINTER.fullmatch(rule_pointer) is None:
            _issue(errors, "KPI_RULE", "traceability", f"/instance/coverage/{index}/planRulePointer",
                   "A declared plan-rule link must identify an exact measurableRules entry.")
        pointer = row.get("kpiPointer")
        if not _present(pointer):
            continue  # Generic criteria do not need a stakeholder KPI.
        match = KPI_POINTER.fullmatch(pointer)
        if match is None:
            _issue(errors, "KPI_POINTER", "traceability", f"/instance/coverage/{index}/kpiPointer",
                   "Reference the exact targetTps or maxResponseTimeMs numeric target leaf.")
            continue
        mappings.setdefault(pointer, []).append((index, row))

    for index, kpi in enumerate(_rows(requirements, "/kpis")):
        if absent(selected) or kpi.get("sutRef") != selected or kpi.get("apiRef") not in active:
            continue
        populated = [field for field in KPI_CONTRACTS if _present(kpi.get(field))]
        if not populated:
            _issue(gaps, "KPI_TARGET", "requirements", f"/kpis/{index}",
                   "Supply the applicable explicit target; an empty KPI does not define acceptance.")
        for field in populated:
            pointer = f"/kpis/{index}/{field}"
            rows = [(i, row) for i, row in mappings.get(pointer, []) if row.get("status") == "mapped"]
            if not rows:
                _issue(gaps, "KPI_MAPPING", "requirements", pointer,
                       "Map this selected active API target to an exact plan rule in traceability coverage.")
            for coverage_index, row in rows:
                _check_kpi_rule(docs, kpi, field, row, coverage_index, gaps, errors)


def _check_kpi_rule(docs: dict, kpi: dict, field: str, row: dict, index: int,
                    gaps: list, errors: list) -> None:
    base = f"/instance/coverage/{index}"
    pointer = row.get("planRulePointer")
    if absent(pointer):
        _issue(gaps, "KPI_RULE", "traceability", base + "/planRulePointer", "Supply the exact mapped plan-rule pointer.")
        return
    match = PLAN_RULE_POINTER.fullmatch(pointer)
    rule = _value(docs["plan"], pointer)
    if match is None:
        _issue(errors, "KPI_RULE", "traceability", base + "/planRulePointer",
               "The KPI mapping must resolve to an exact measurableRules entry.")
        return
    if not isinstance(rule, dict):
        return  # The reference checker owns unresolved references.
    criterion = _value(docs["plan"], f"/acceptanceCriteria/{match[1]}/criterionRef")
    dimensions = (("apiRef", "apiRef"), ("sutRef", "sutRef"),
                  ("transactionDefinition", "transactionDefinition"), ("window", "measurementWindow"))
    for target_field, source_field in dimensions:
        expected, actual = kpi.get(source_field), rule.get(target_field)
        if absent(expected) or (absent(actual) and target_field != "window"):
            _issue(gaps, "KPI_DIMENSION", "plan", pointer + "/" + target_field,
                   "Supply this mapped goal dimension and its explicit stakeholder source.")
        elif not absent(actual) and actual != expected:
            _issue(errors, "KPI_DIMENSION", "plan", pointer + "/" + target_field,
                   "The mapped rule changes a stakeholder API, SUT, transaction or observation-window dimension.")
    for key in ("criterionRef", "apiRef", "sutRef"):
        if absent(row.get(key)):
            _issue(gaps, "KPI_COVERAGE_SCOPE", "traceability", base + "/" + key, "Identify the mapped KPI scope.")
        elif row[key] != kpi.get(key):
            _issue(errors, "KPI_COVERAGE_SCOPE", "traceability", base + "/" + key,
                   "Coverage scope does not match its referenced stakeholder KPI.")
    if not absent(criterion) and criterion != kpi.get("criterionRef"):
        _issue(errors, "KPI_CRITERION", "plan", pointer, "The plan rule belongs to another stakeholder criterion.")
    unit, operators = KPI_CONTRACTS[field]
    if not absent(rule.get("unit")) and rule["unit"] != unit:
        _issue(errors, "KPI_UNIT", "plan", pointer + "/unit", "Use the canonical unit for this exact stakeholder target.")
    if not absent(rule.get("operator")) and rule["operator"] not in operators:
        _issue(errors, "KPI_DIRECTION", "plan", pointer + "/operator", "The operator must preserve the target's minimum or maximum direction.")
    threshold, target = rule.get("threshold"), kpi.get(field)
    if _present(threshold):
        if not _number(threshold) or not _number(target):
            _issue(errors, "KPI_THRESHOLD", "plan", pointer + "/threshold", "A mapped numeric goal needs a numeric threshold.")
        elif (field == "targetTps" and threshold < target) or (field == "maxResponseTimeMs" and threshold > target):
            _issue(errors, "KPI_WEAKENED", "plan", pointer + "/threshold", "The plan threshold weakens the stated stakeholder target.")
    if field == "maxResponseTimeMs":
        if absent(kpi.get("responseTimePercentile")) or absent(rule.get("percentile")):
            _issue(gaps, "KPI_PERCENTILE", "plan", pointer + "/percentile", "Supply the stakeholder latency percentile without guessing.")
        elif rule["percentile"] != kpi["responseTimePercentile"]:
            _issue(errors, "KPI_PERCENTILE", "plan", pointer + "/percentile", "Preserve the stakeholder latency percentile.")
    elif rule.get("percentile") is not None:
        _issue(errors, "KPI_PERCENTILE", "plan", pointer + "/percentile", "A successful-transaction rate target is not a latency percentile target.")


def _result_records(results: dict):
    """Yield only explicit post-run observations; requested values and snapshots are not measurements."""
    for i, api in enumerate(_rows(results, "/apiResults")):
        base = f"/apiResults/{i}"
        yield base, api, ("startTime", "endTime"), ()
        for j, phase in enumerate(api.get("timelineResults", [])):
            yield f"{base}/timelineResults/{j}", phase, ("achieved",), ("evidenceRef",)
        for j, assertion in enumerate(api.get("assertions", [])):
            yield f"{base}/assertions/{j}", assertion, ("evaluatedCount", "passedCount", "failedCount"), ("evidenceRef",)
    for i, journey in enumerate(_rows(results, "/journeyResults")):
        yield f"/journeyResults/{i}", journey, ("achieved", "steps"), ("evidenceRefs",)
    for i, criterion in enumerate(_rows(results, "/criterionResults")):
        yield f"/criterionResults/{i}", criterion, (), ()
        for j, rule in enumerate(criterion.get("ruleResults", [])):
            yield f"/criterionResults/{i}/ruleResults/{j}", rule, ("observedValue", "windowStart", "windowEnd", "sampleCount"), ("evidenceRefs",)
    for i, row in enumerate(_rows(results, "/dataObservations")):
        yield f"/dataObservations/{i}", row, ("consumedRows", "reusedRows", "exhausted", "maxObservedConcurrentRequestsPerRecord", "sideEffectsObserved"), ("validationEvidenceRefs",)
    for pointer, fields, evidence in (
        ("/workloadDeliveryResult", ("observedValue",), ("evidenceRefs",)),
        ("/stopOutcome", ("producersStoppedAt", "drainCompletedAt", "outstandingWork", "timeoutActionTaken"), ("evidenceRefs",)),
        ("/comparisonResults", ("observed",), ("comparabilityEvidenceRefs",)),
        ("/overallResult", (), ()),
    ):
        row = _value(results, pointer)
        if isinstance(row, dict):
            yield pointer, row, fields, evidence


def _observed(value: object) -> bool:
    if isinstance(value, dict):
        return any(_observed(item) for item in value.values())
    if isinstance(value, list):
        return any(_observed(item) for item in value)
    return _present(value)


def _check_results(docs: dict, gaps: list, errors: list) -> None:
    results = docs["results"]
    occurred = _value(results, "/runInfo/executionStatus") in EXECUTED_STATUSES
    for pointer, record, fields, evidence_fields in _result_records(results):
        claim = any(record.get(key) in CLAIMED_OUTCOMES for key in ("status", "result", "usageConstraintsResult"))
        observed = any(_observed(record.get(field)) for field in fields)
        if not (claim or observed):
            continue
        if not occurred:
            _issue(errors, "UNEXECUTED_RESULT", "results", pointer,
                   "Unexecuted results cannot contain test outcomes or achieved observations, including invented zero counts.")
        if evidence_fields and not any(_present(record.get(field)) for field in evidence_fields):
            _issue(errors if claim else gaps, "RESULT_EVIDENCE", "results", pointer,
                   "Name the actual evidence supporting this recorded observation or outcome.")
        if claim and not evidence_fields and not _aggregate_evidence(results, pointer, record):
            _issue(errors, "RESULT_EVIDENCE", "results", pointer,
                   "An aggregate outcome needs named supporting test evidence, not only a status.")
    # Readiness and preparation can be evidenced before load; they are not a test pass.
    for pointer in ("/preRunChecks", "/sqlChecks"):
        for i, check in enumerate(_rows(results, pointer)):
            if (check.get("status") in CLAIMED_OUTCOMES or _observed(check.get("observed"))) and absent(check.get("evidenceRef")):
                _issue(errors, "RESULT_EVIDENCE", "results", f"{pointer}/{i}/evidenceRef", "A check outcome needs its own evidence reference.")
            if pointer == "/sqlChecks" and check.get("phase") == "post-run" and not occurred and check.get("status") in CLAIMED_OUTCOMES:
                _issue(errors, "UNEXECUTED_RESULT", "results", f"{pointer}/{i}", "A post-run check cannot claim an outcome for an unexecuted test.")
    for i, row in enumerate(_rows(results, "/dataObservations")):
        if any(_present(row.get(key)) for key in ("preparedRows", "uniqueRows")) and not _present(row.get("validationEvidenceRefs")):
            _issue(gaps, "PREPARATION_EVIDENCE", "results", f"/dataObservations/{i}/validationEvidenceRefs",
                   "Recorded preparation counts need their own evidence; they do not prove load execution.")
    if occurred:
        for pointer in ("/executionResultId", "/runInfo/sutId", "/runInfo/swarmId", "/runInfo/runId", "/runInfo/operator",
                        "/runInfo/startTime", "/runInfo/endTime", "/runInfo/bundleId", "/runInfo/scenarioId",
                        "/references/actualRuntimeConfigSnapshotRef", "/references/actualRuntimeConfigSha256"):
            if absent(_value(results, pointer)):
                _issue(gaps, "RUN_IDENTITY", "results", pointer, "Identify the actual run and retained runtime snapshot; a configured plan is insufficient.")
        if _value(results, "/runInfo/executionStatus") in CLAIMED_OUTCOMES and not _aggregate_evidence(results, "/overallResult", {}):
            _issue(errors, "RESULT_EVIDENCE", "results", "/runInfo/executionStatus", "A claimed run outcome requires named supporting test evidence.")
        _check_stop(docs, gaps, errors)
    elif _present(_value(results, "/runInfo/startTime")) or _present(_value(results, "/runInfo/endTime")):
        _issue(errors, "UNEXECUTED_RESULT", "results", "/runInfo", "Actual run timestamps contradict the declared unexecuted state.")
    _check_rule_snapshots(docs, gaps, errors, occurred)


def _aggregate_evidence(results: dict, pointer: str, record: dict) -> bool:
    if pointer.startswith("/apiResults/"):
        return any(_present(row.get("evidenceRef")) for key in ("assertions", "timelineResults") for row in record.get(key, []))
    if pointer.startswith("/criterionResults/"):
        return any(_present(row.get("evidenceRefs")) for row in record.get("ruleResults", []))
    if pointer == "/overallResult":
        return any(_present(rule.get("evidenceRefs")) for criterion in _rows(results, "/criterionResults") for rule in criterion.get("ruleResults", [])) or any(
            _present(row.get("artifactRef")) or _present(row.get("graphUrl")) for row in _rows(results, "/monitoring/evidence")) or any(
            _present(row.get("evidenceRef")) for api in _rows(results, "/apiResults") for key in ("assertions", "timelineResults") for row in api.get(key, [])
        ) or _present(_value(results, "/workloadDeliveryResult/evidenceRefs"))
    return False


def _check_stop(docs: dict, gaps: list, errors: list) -> None:
    results = docs["results"]
    for field in ("producersStoppedAt", "outstandingWork", "evidenceRefs"):
        if not _present(_value(results, "/stopOutcome/" + field)):
            _issue(gaps, "STOP_EVIDENCE", "results", "/stopOutcome/" + field,
                   "An executed run needs verified producer-stop and remaining-work evidence.")
    outstanding = _value(results, "/stopOutcome/outstandingWork")
    drained = _present(_value(results, "/stopOutcome/drainCompletedAt"))
    if outstanding == 0 and not drained:
        _issue(gaps, "DRAIN_EVIDENCE", "results", "/stopOutcome/drainCompletedAt", "Record verified drain completion.")
    elif _number(outstanding) and outstanding > 0:
        if drained:
            _issue(errors, "DRAIN_CONTRADICTION", "results", "/stopOutcome", "Drain completion contradicts outstanding work.")
        for role, pointer in (("results", "/stopOutcome/timeoutActionTaken"), ("plan", "/entryExit/drainTimeoutAction")):
            if absent(_value(docs[role], pointer)):
                _issue(gaps, "DRAIN_ACTION", role, pointer, "Record the declared and actual response to incomplete draining.")


def _check_rule_snapshots(docs: dict, gaps: list, errors: list, occurred: bool) -> None:
    for i, row in enumerate(_rows(docs["traceability"], "/instance/coverage")):
        plan_pointer, result_pointer = row.get("planRulePointer"), row.get("resultPointer")
        if not _present(plan_pointer) or not _present(result_pointer):
            continue
        if PLAN_RULE_POINTER.fullmatch(plan_pointer) is None:
            continue  # Other reference semantics belong to the reference checker.
        result_match = RESULT_RULE_POINTER.fullmatch(result_pointer)
        planned, recorded = _value(docs["plan"], plan_pointer), _value(docs["results"], result_pointer)
        if result_match is None:
            _issue(errors, "RESULT_RULE_POINTER", "traceability", f"/instance/coverage/{i}/resultPointer",
                   "An observed acceptance-rule link must point to an exact criterionResults ruleResults record.")
        elif isinstance(planned, dict) and isinstance(recorded, dict):
            plan_match = PLAN_RULE_POINTER.fullmatch(plan_pointer)
            planned_criterion = _value(docs["plan"], f"/acceptanceCriteria/{plan_match[1]}/criterionRef")
            recorded_criterion = _value(docs["results"], f"/criterionResults/{result_match[1]}/criterionRef")
            if not absent(planned_criterion) and not absent(recorded_criterion) and planned_criterion != recorded_criterion:
                _issue(errors, "RESULT_RULE_CRITERION", "results", result_pointer,
                       "The result record belongs to another criterion than its referenced plan rule.")
            copied = recorded.get("rule")
            if copied is None:
                if occurred and (recorded.get("result") in CLAIMED_OUTCOMES or _observed(recorded.get("observedValue"))):
                    _issue(gaps, "RESULT_RULE_SNAPSHOT", "results", result_pointer + "/rule", "Retain the exact plan-rule snapshot used for this assessment.")
            elif canonical_hash(copied) != canonical_hash(planned):
                _issue(errors, "RESULT_RULE_SNAPSHOT", "results", result_pointer + "/rule", "The copied result rule differs from its explicitly referenced plan rule.")


def check_measurements(docs: dict) -> tuple[list[dict], list[dict]]:
    """Return handoff gaps and explicit contradictions without calculating an outcome."""
    gaps: list[dict] = []
    errors: list[dict] = []
    _check_kpis(docs, gaps, errors)
    _check_results(docs, gaps, errors)
    return gaps, errors
