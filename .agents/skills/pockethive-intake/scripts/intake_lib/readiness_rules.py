"""Responsibility: identify applicable missing intake decisions and incompatible choices.
Must not: approve work, assess live readiness or calculate test outcomes. Contract: intake-contract.md.
"""
from __future__ import annotations

from .data_rules import participating_entities, requires_secrets
from .input_values import absent
from .pointers import resolve
from .errors import IntakeError
from .applicability import PARTICIPATING_MODES, selected_sut


def check_readiness(docs: dict) -> tuple[list[dict], list[dict]]:
    gaps, errors = [], []
    requirements, plan = docs["requirements"], docs["plan"]

    def issue(collection: list, code: str, role: str, pointer: str, message: str) -> None:
        collection.append(IntakeError(code, message, role, pointer).issue)

    def need(role: str, pointer: str, message: str = "Supply the relevant explicit decision or source.") -> object:
        try:
            value = resolve(docs[role], pointer)
        except IntakeError:
            value = None
        if absent(value) or value == [] or value == {}:
            issue(gaps, "REQUIRED_INPUT", role, pointer, message)
        return value

    for pointer in ("/project/name", "/project/owner", "/project/objective", "/successCriteria"):
        need("requirements", pointer)
    for pointer in ("/owner", "/sutId", "/generation/workerVersion", "/generation/pipeline",
                    "/generation/bundleId", "/generation/scenarioId"):
        need("plan", pointer)
    for pointer in ("/bundleGeneration/bundleId", "/bundleGeneration/scenarioId"):
        need("requirements", pointer)
    if plan["approval"]["status"] != "approved":
        issue(gaps, "PLAN_REVIEW", "plan", "/approval", "The plan needs its existing explicit review/approval record.")
    else:
        for pointer in ("/approval/approvedBy", "/approval/approvedAt"):
            need("plan", pointer)
    if plan["generation"]["approval"]["status"] != "approved":
        issue(gaps, "GENERATION_REVIEW", "plan", "/generation/approval", "Generation approval is separate from runtime permission.")
    else:
        need("plan", "/generation/approval/approvedBy")
    instance = docs["traceability"]["instance"]
    if instance["review"]["status"] != "confirmed" or absent(instance["review"]["evidenceRef"]):
        issue(gaps, "HUMAN_REVIEW", "traceability", "/instance/review", "Reference the human review of this exact document set.")
    for index, question in enumerate(instance["questions"]):
        if question["status"] != "answered" and question["blockingStage"] != "execution":
            issue(gaps, "OPEN_QUESTION", "traceability", f"/instance/questions/{index}", "A material question remains unanswered.")
        elif question["status"] == "answered" and absent(question["answerRef"]):
            issue(errors, "ANSWER_EVIDENCE", "traceability", f"/instance/questions/{index}/answerRef", "Answered questions need a supplied answer reference.")
    selected = selected_sut(requirements, plan)
    if selected is not None:
        sut_index = requirements["suts"].index(selected)
        for field in ("name", "type", "endpoints"):
            need("requirements", f"/suts/{sut_index}/{field}")

    apis = {api["apiId"]: (index, api) for index, api in enumerate(requirements["templates"]) if api["apiId"] is not None}
    executions = [entry for entry in plan["apiExecution"] if entry.get("apiRef") is not None]
    if not executions:
        need("plan", "/apiExecution/0/apiRef", "Identify the APIs and their purpose in the test.")
    for api_id, (index, api) in apis.items():
        if not any(entry["apiRef"] == api_id for entry in executions):
            issue(gaps, "API_CLASSIFICATION", "requirements", f"/templates/{index}", "Classify this API as load, readiness, auth prerequisite or excluded.")
    loads = []
    protected = requires_secrets(requirements, plan)
    for index, execution in enumerate(plan["apiExecution"]):
        base = f"/apiExecution/{index}"
        if execution.get("apiRef") is None:
            continue
        mode = need("plan", base + "/mode")
        if mode in ("excluded", "readiness-only", "auth-prerequisite"):
            need("plan", base + "/reason")
        if mode not in PARTICIPATING_MODES:
            continue
        if mode == "load":
            loads.append(execution)
        api_pair = apis.get(execution["apiRef"])
        if api_pair is None:
            continue
        api_index, api = api_pair
        api_base = f"/templates/{api_index}"
        for field in ("name", "serviceId", "callId", "endpointRef", "protocol", "authorization/type"):
            need("requirements", api_base + "/" + field)
        if api["protocol"] not in (None, "HTTP", "HTTPS"):
            issue(gaps, "TRANSPORT_REPRESENTATION", "requirements", api_base + "/protocol", "This HTTP-shaped authoring contract needs a reviewed transport extension before handoff.")
        if api["protocol"] in ("HTTP", "HTTPS"):
            for field in ("method", "path", "expectedResponse/httpStatuses"):
                need("requirements", api_base + "/" + field)
        auth_type = api["authorization"].get("type")
        if auth_type == "oauth2-client-credentials":
            for field in ("clientAuthentication", "reuse", "refreshBeforeExpirySeconds"):
                need("requirements", api_base + "/authorization/" + field)
            if selected is not None:
                for field in ("tokenUrl", "credentialRef"):
                    need("requirements", f"/suts/{requirements['suts'].index(selected)}/oauth2/{field}")
                credential = (selected.get("oauth2") or {}).get("credentialRef")
                if not absent(credential) and not any(binding.get("credentialRef") == credential for binding in plan["secretInjection"]["bindings"]):
                    issue(gaps, "AUTH_CREDENTIAL", "plan", "/secretInjection/bindings", "Bind the selected OAuth credential through the declared injection mechanism.")
        elif auth_type == "bearer":
            for field in ("apiRef", "tokenJsonPointer", "expiresInJsonPointer", "reuse", "refreshBeforeExpirySeconds"):
                need("requirements", api_base + "/authorization/tokenSource/" + field)
        if selected and api.get("endpointRef") in selected["endpoints"]:
            endpoint = selected["endpoints"][api["endpointRef"]]
            if absent(endpoint.get("kind")) or absent(endpoint.get("baseUrl")):
                issue(gaps, "ENDPOINT_SETTINGS", "requirements", f"/suts/{requirements['suts'].index(selected)}/endpoints", "Declare endpoint transport and address explicitly.")
        if mode in ("load", "readiness-only"):
            for field in ("maxInFlight", "timeoutMs", "retries", "keepAlive", "connectionReuse", "tlsVerify"):
                need("plan", base + "/runtime/" + field)
            if (execution["runtime"].get("retries") or 0) > 0:
                need("requirements", api_base + "/idempotency/required")
                need("requirements", api_base + "/idempotency/rule")
        if mode == "readiness-only":
            for field in ("requestCount", "beforeLoad", "requiredAssertion"):
                need("plan", base + "/readiness/" + field)

    model = plan["executionModel"]
    if loads:
        for field in ("relationship", "rateUnit", "arrivalModel", "completion/mode"):
            need("plan", "/executionModel/" + field)
        for pointer in ("/processorAllocation/strategy", "/processorAllocation/onCapacityExceeded",
                        "/safety/maxRunDurationSeconds", "/safety/maxTotalRequests", "/safety/requestBudgetScope",
                        "/safety/observationIntervalSeconds", "/entryExit/drainTimeoutSeconds",
                        "/entryExit/drainTimeoutAction", "/entryExit/stopProcedureRef", "/cleanup/owner"):
            need("plan", pointer)
        if plan["processorAllocation"]["strategy"] == "shared":
            need("plan", "/processorAllocation/sharedMaxInFlight")
        for field in ("metric", "operator", "threshold", "window", "minimumSamples"):
            need("plan", "/workloadDelivery/" + field)
        if model["arrivalModel"] == "closed-loop":
            for field in ("concurrentUsers", "completionMode", "pacingMs"):
                need("plan", "/executionModel/closedLoop/" + field)
            completion = model["closedLoop"]["completionMode"]
            if completion in ("duration", "iterations"):
                need("plan", "/executionModel/closedLoop/" + ("durationSeconds" if completion == "duration" else "iterationsPerUser"))
            if any(not absent(phase.get("startRate")) or not absent(phase.get("endRate")) for entry in loads for phase in entry["timeline"]) or model["journeyTimeline"]:
                issue(errors, "WORKLOAD_MODEL", "plan", "/executionModel", "Closed-loop users cannot be replaced by rate timelines.")
        elif model["arrivalModel"] == "open-loop":
            timelines = [("/executionModel/journeyTimeline", model["journeyTimeline"])] if model["relationship"] == "sequential" else [
                (f"/apiExecution/{index}/timeline", item["timeline"]) for index, item in enumerate(plan["apiExecution"]) if item["mode"] == "load"]
            for pointer, timeline in timelines:
                if not timeline:
                    need("plan", pointer)
                for index, phase in enumerate(timeline):
                    for field in ("phase", "durationSeconds", "startRate", "endRate", "interpolation"):
                        need("plan", f"{pointer}/{index}/{field}")
        if model["relationship"] == "sequential":
            need("plan", "/executionModel/sequence")
            for index, execution in enumerate(plan["apiExecution"]):
                if execution["mode"] != "load":
                    continue
                pointer = f"/apiExecution/{index}"
                declared = need("plan", pointer + "/requestsPerJourney")
                count = sum(1 for step in model["sequence"] if step.get("apiRef") == execution["apiRef"])
                if declared is not None and model["sequence"] and all(step.get("apiRef") is not None for step in model["sequence"]) and declared != count:
                    issue(errors, "JOURNEY_COUNT", "plan", pointer + "/requestsPerJourney", "The declared count must equal the explicit sequence occurrences of this API.")
                if any(not absent(phase.get("startRate")) or not absent(phase.get("endRate")) for phase in execution["timeline"]):
                    issue(errors, "WORKLOAD_MODEL", "plan", pointer + "/timeline", "A journey workload has one schedule; API rate timelines cannot define a competing schedule.")
            if model["rateUnit"] not in (None, "journeys-per-second"):
                issue(errors, "JOURNEY_RATE_UNIT", "plan", "/executionModel/rateUnit", "Sequential workloads use an explicit journey rate unit.")
            for index, step in enumerate(model["sequence"]):
                for field in ("stepId", "apiRef", "thinkTimeMs"):
                    need("plan", f"/executionModel/sequence/{index}/{field}")
        if model["completion"]["mode"] == "asynchronous":
            for field in ("assertionRef", "maxDurationSeconds", "waitPolicyRef"):
                need("plan", "/executionModel/completion/" + field)

    if any(step.get("correlations") for step in model["sequence"]):
        need("plan", "/executionModel/relationship")
        need("plan", "/executionModel/correlationFailureAction")
    for index, step in enumerate(model["sequence"]):
        for correlation_index, correlation in enumerate(step.get("correlations", [])):
            pointer = f"/executionModel/sequence/{index}/correlations/{correlation_index}"
            for field in ("correlationId", "fromStepRef", "toStepRef", "required"):
                need("plan", pointer + "/" + field)
            if correlation.get("responsePath") is None:
                issue(gaps, "REQUIRED_INPUT", "plan", pointer + "/responsePath",
                      "Supply the exact response JSON Pointer; an empty string explicitly selects its root.")

    for index, entity in participating_entities(requirements, plan):
        base = f"/testData/entities/{index}"
        for field in ("owner", "requiredCount", "columns", "usage/reuseAllowed", "usage/maxConcurrentRequestsPerRecord",
                      "usage/onExhaustion", "sideEffects/mutatesState", "sideEffects/resetRequired"):
            need("requirements", base + "/" + field)
        if entity["sideEffects"]["resetRequired"] is True:
            need("requirements", base + "/sideEffects/resetInstructions")
            need("requirements", base + "/sideEffects/owner")
        prepared = [(i, row) for i, row in enumerate(plan["dataPreparation"]) if row["entityRef"] == entity["entityId"]]
        if not prepared:
            issue(gaps, "DATA_PREPARATION", "plan", "/dataPreparation", "Declare preparation for each used dataset.")
        for i, row in prepared:
            for field in ("sourceSutRef", "sourceType", "rowCount", "preparation", "readinessChecks"):
                need("plan", f"/dataPreparation/{i}/{field}")
            if model["relationship"] == "sequential":
                for field in ("unit", "releaseCondition", "sharingScope"):
                    need("plan", f"/dataPreparation/{i}/allocation/{field}")
            if row["allocation"]["sharingScope"] == "shared-consumers":
                need("plan", f"/dataPreparation/{i}/allocation/mechanismRef")
    if protected:
        for field in ("owner", "type", "implementationRef", "bindings"):
            need("plan", "/secretInjection/" + field)
        for index, binding in enumerate(plan["secretInjection"]["bindings"]):
            base = f"/secretInjection/bindings/{index}"
            need("plan", base + "/credentialRef")
            kind = plan["secretInjection"]["type"]
            if kind == "vault":
                need("plan", base + "/resolverRef")
            if kind in ("env", "vault"):
                field = "environmentVariables" if kind == "env" else "fieldMapping"
                values = need("plan", base + "/" + field)
                if isinstance(values, dict) and any(absent(value) for value in values.values()):
                    issue(gaps, "SECRET_BINDING", "plan", base + "/" + field, "Every declared secret binding must name its explicit field or environment variable.")
    mock_mode = need("plan", "/mockSetup/mode")
    if mock_mode == "existing":
        need("plan", "/mockSetup/existingStubsRef")
    elif mock_mode == "generate-stubs":
        need("plan", "/mockSetup/blueprint")
    for index, dependency in enumerate(plan["mockSetup"]["dependencies"]):
        for field in ("endpointRef", "disposition"):
            need("plan", f"/mockSetup/dependencies/{index}/{field}")
        if dependency["disposition"] == "mocked":
            need("plan", f"/mockSetup/dependencies/{index}/configurationRef")

    for index, criterion in enumerate(requirements["successCriteria"]):
        need("requirements", f"/successCriteria/{index}/criterionId")
        need("requirements", f"/successCriteria/{index}/description")
        if criterion["criterionId"] is not None and not any(row["criterionRef"] == criterion["criterionId"] for row in plan["acceptanceCriteria"]):
            issue(gaps, "CRITERION_MAPPING", "plan", "/acceptanceCriteria", "Every stated criterion needs its applicable test rules.")
    for index, criterion in enumerate(plan["acceptanceCriteria"]):
        base = f"/acceptanceCriteria/{index}"
        need("plan", base + "/criterionRef")
        need("plan", base + "/measurableRules")
        for i, rule in enumerate(criterion["measurableRules"]):
            pointer = f"{base}/measurableRules/{i}"
            for field in ("metric", "operator", "threshold", "window", "minimumSamples", "unit",
                          "measurementDefinition/sourceRef", "measurementDefinition/population", "measurementDefinition/aggregation"):
                need("plan", pointer + "/" + field)
            if rule["percentile"] is not None:
                for field in ("latencyStart", "latencyEnd", "timeoutTreatment", "retryTreatment", "warmupPolicy", "drainPolicy"):
                    need("plan", pointer + "/measurementDefinition/" + field)
    if not absent(plan["comparison"]["baselineExecutionRef"]):
        need("plan", "/comparison/comparabilityChecks")
        need("plan", "/comparison/decisionRule")
        need("plan", "/environmentQualification/sutSnapshotRef")
    return gaps, errors
