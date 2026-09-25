"""Explicit synthetic inputs for positive document-consistency qualification.

These test values and review records are fictional. They authorise no real
client work or operation and make no claim about PocketHive runtime readiness.
"""

from __future__ import annotations

import hashlib
import json

from test_intake_cli import dictionaries_in


def prepare_complete_intake(case):
    case.initialise()
    requirements = case.read_document("requirements.yaml")
    plan = case.read_document("test-plan.yaml")
    traceability = case.read_document("traceability.yaml")
    requirements["project"].update({
        "name": "Synthetic intake qualification",
        "owner": "qualification-client",
        "objective": "Observe ten successful bodyless reads in the explicitly selected two-second test.",
    })
    sut = requirements["suts"][0]
    sut.update({"sutId": "qualification-sut", "name": "Synthetic target", "type": "sandbox",
                "endpoints": {"read": {"kind": "HTTP", "baseUrl": "https://qualification.invalid"}}})
    sut.pop("oauth2", None)
    api = requirements["templates"][0]
    api.update({"apiId": "API-READ", "name": "Read synthetic status", "serviceId": "qualification",
                "callId": "read", "endpointRef": "read", "protocol": "HTTP", "method": "GET", "path": "/status",
                "payloadBindings": []})
    api["authorization"] = {"type": "none"}
    api["expectedResponse"] = {"httpStatuses": [200], "bodyChecks": []}
    api["idempotency"].update({"required": False, "headerName": None, "rule": "The supplied API is read-only."})
    requirements["testData"]["entities"] = []
    requirements["databases"] = []
    requirements["successCriteria"] = [{"criterionId": "CRIT-READ", "description": "Ten successful HTTP 200 reads in two seconds."}]
    requirements["kpis"] = []
    requirements["bundleGeneration"].update({"bundleId": "qualification-bundle", "scenarioId": "qualification-scenario"})
    plan.update({"owner": "qualification-engineer", "sutId": "qualification-sut"})
    plan["approval"].update({"status": "approved", "approvedBy": "synthetic-reviewer",
                             "approvedAt": "2026-09-16T12:00:00Z"})
    plan["generation"].update({
        "bundleId": "qualification-bundle", "scenarioId": "qualification-scenario", "workerVersion": "v0.15.28",
        "pipeline": ["generator", "request-builder", "processor", "postprocessor"],
    })
    plan["generation"]["approval"].update({"status": "approved", "approvedBy": "synthetic-reviewer"})
    plan["executionModel"].update({"relationship": "independent", "rateUnit": "requests-per-second", "arrivalModel": "open-loop"})
    plan["executionModel"]["completion"]["mode"] = "synchronous"
    execution = plan["apiExecution"][0]
    execution.update({"apiRef": "API-READ", "mode": "load"})
    execution["timeline"] = [{"phase": "constant", "durationSeconds": 2, "startRate": 5, "endRate": 5, "interpolation": "constant"}]
    execution["runtime"].update({"maxInFlight": 2, "timeoutMs": 1000, "retries": 0,
                                 "keepAlive": True, "connectionReuse": "reuse", "tlsVerify": True})
    plan["processorAllocation"].update({"strategy": "per-api", "onCapacityExceeded": "abort"})
    plan["dataPreparation"] = []
    plan["secretInjection"]["notRequiredReason"] = "The supplied synthetic API explicitly uses no authentication."
    plan["mockSetup"]["mode"] = "not-required"
    plan["safety"].update({"maxRunDurationSeconds": 3, "maxTotalRequests": 10,
                           "requestBudgetScope": "All request attempts; no readiness/auth calls or retries.",
                           "observationIntervalSeconds": 1})
    plan["entryExit"].update({"drainTimeoutSeconds": 1, "drainTimeoutAction": "abort",
                              "stopProcedureRef": "synthetic-stop-procedure"})
    plan["cleanup"]["owner"] = "qualification-operator"
    plan["workloadDelivery"].update({"metric": "offered-request-count", "operator": "equals",
                                     "threshold": 10, "window": "2s", "minimumSamples": 10})
    criterion = plan["acceptanceCriteria"][0]
    criterion["criterionRef"] = "CRIT-READ"
    rule = criterion["measurableRules"][0]
    rule.update({"metric": "successful-http-read-count", "operator": "equals", "threshold": 10,
                 "window": "2s", "minimumSamples": 10, "apiRef": "API-READ", "sutRef": "qualification-sut",
                 "transactionDefinition": "One completed /status read returning HTTP 200",
                 "unit": "requests", "percentile": None})
    rule["measurementDefinition"].update({
        "sourceRef": "synthetic-request-event-contract", "population": "All scheduled API-READ requests",
        "includedOutcomes": ["HTTP 200"], "excludedOutcomes": [],
        "aggregation": "count", "denominator": None,
    })
    source = case.workspace / "explicit-synthetic-inputs.json"
    source.write_text(json.dumps({
        "qualificationOnly": True,
        "requirements": requirements,
        "plan": plan,
        "review": {"actor": "synthetic-reviewer", "decision": "Approved for this document-consistency test only."},
    }, indent=2), encoding="utf-8")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    instance = traceability["instance"]
    instance["intake"]["source"] = {"artifactRef": str(source), "sha256": digest, "kind": "file"}
    for question in instance["questions"]:
        question.update({"status": "answered", "answerRef": str(source)})
    instance["questions"].append({
        "id": "Q-FUTURE-RUN", "targets": [{"document": "results", "pointer": "/runInfo/startTime"}],
        "question": "What start time will be observed if a separately authorised run occurs?",
        "owner": None, "blockingStage": "execution", "status": "open", "answerRef": None,
    })
    instance["provenance"] = [
        {"target": {"document": "plan", "pointer": pointer}, "kind": "approval",
         "sources": [{"artifactRef": str(source), "pointer": "/plan" + pointer, "sha256": digest}],
         "confirmationRef": str(source), "calculation": None}
        for pointer in ("/approval", "/generation/approval")
    ] + [
        {"target": {"document": document, "pointer": ""}, "kind": "client-statement",
         "sources": [{"artifactRef": str(source), "pointer": "/" + document, "sha256": digest}],
         "confirmationRef": None, "calculation": None}
        for document in ("requirements", "plan")
    ]
    case.write_document("requirements.yaml", requirements)
    case.write_document("test-plan.yaml", plan)
    case.write_document("traceability.yaml", traceability)
    _, output = case.invoke("finalise", "--documents", case.documents, expected_exit=0)
    hashes = [record["reviewContentSha256"] for record in dictionaries_in(output) if "reviewContentSha256" in record]
    case.assertEqual(1, len(hashes), output)
    traceability = case.read_document("traceability.yaml")
    traceability["instance"]["review"] = {"status": "confirmed", "evidenceRef": str(source), "contentSha256": hashes[0]}
    case.write_document("traceability.yaml", traceability)
    case.invoke("finalise", "--documents", case.documents, expected_exit=0)
    return source
