"""Responsibility: check identities and explicit references in schema-valid intake documents.
Must not: infer missing targets, select source alternatives, perform I/O or decide readiness.
Contract: intake-contract.md; document shapes belong to the packaged schemas.
"""
from __future__ import annotations

from .applicability import active_execution_rows
from .errors import IntakeError
from .pointers import escape, resolve


_DUPLICATE_ID = "DUPLICATE_ID"
_UNKNOWN_REFERENCE = "UNKNOWN_REFERENCE"
_AMBIGUOUS_REFERENCE = "AMBIGUOUS_REFERENCE"
_REFERENCE_MISMATCH = "REFERENCE_MISMATCH"
_TOKEN_DEPENDENCY_CYCLE = "TOKEN_DEPENDENCY_CYCLE"


class _References:
    """Responsibility: resolve the declared reference graph without changing its documents.
    Must not: calculate applicability gaps or validate schemas. Contract: intake-contract.md.
    """

    def __init__(self, docs: dict) -> None:
        self.documents = docs
        self.requirements = docs["requirements"]
        self.plan = docs["plan"]
        self.results = docs["results"]
        self.issues: list[dict] = []
        self.selected_sut = self.plan.get("sutId")
        self.suts = self.index(self.requirements.get("suts", []), "sutId", "requirements", "/suts")
        self.apis = self.index(self.requirements.get("templates", []), "apiId", "requirements", "/templates")
        self.entities = self.index(self.requirements.get("testData", {}).get("entities", []),
                                   "entityId", "requirements", "/testData/entities")
        self.databases = self.index(self.requirements.get("databases", []), "dbId", "requirements", "/databases")
        self.criteria = self.index(self.requirements.get("successCriteria", []),
                                   "criterionId", "requirements", "/successCriteria")
        self.plan_apis = self.index(self.plan.get("apiExecution", []), "apiRef", "plan", "/apiExecution")
        self.plan_criteria = self.index(self.plan.get("acceptanceCriteria", []),
                                        "criterionRef", "plan", "/acceptanceCriteria")
        self.columns: dict[str, dict] = {}
        self.sources: dict[str, dict] = {}
        self.queries: dict[str, dict] = {}
        self.used_entities: set[str] = set()

    def issue(self, code: str, document: str, pointer: str, message: str) -> None:
        self.issues.append(IntakeError(code, message, document, pointer).issue)

    def index(self, rows: list, key: str, document: str, pointer: str) -> dict:
        indexed = {}
        for position, row in enumerate(rows):
            identity = row.get(key)
            if identity is None:
                continue
            path = f"{pointer}/{position}"
            if identity in indexed:
                self.issue(_DUPLICATE_ID, document, f"{path}/{escape(key)}",
                           "Populated identities must be unique within their declared scope.")
                indexed[identity] = None
            else:
                indexed[identity] = (row, path)
        return indexed

    def reference(self, value: object, indexed: dict, document: str, pointer: str):
        if value is None:
            return None
        if value not in indexed:
            self.issue(_UNKNOWN_REFERENCE, document, pointer,
                       "Populated reference has no declared target in its required scope.")
            return None
        target = indexed[value]
        if target is None:
            self.issue(_AMBIGUOUS_REFERENCE, document, pointer,
                       "Reference target is ambiguous because its identity is duplicated.")
        return target

    def same(self, value: object, expected: object, document: str, pointer: str) -> None:
        if value is not None and expected is not None and value != expected:
            self.issue(_REFERENCE_MISMATCH, document, pointer,
                       "Populated value disagrees with its authoritative identity or scope.")

    def identities(self) -> None:
        requirement_id = self.requirements.get("requirementId")
        self.same(self.plan.get("requirementId"), requirement_id, "plan", "/requirementId")
        self.same(self.results.get("requirementId"), requirement_id, "results", "/requirementId")
        self.same(self.results.get("planId"), self.plan.get("planId"), "results", "/planId")
        self.same(self.plan.get("requirementsSnapshot", {}).get("version"),
                  self.requirements.get("version"), "plan", "/requirementsSnapshot/version")
        result_refs = self.results.get("references", {})
        for key, expected in (("requirementsVersion", self.requirements.get("version")),
                              ("planVersion", self.plan.get("version")),
                              ("planRevision", self.plan.get("revision"))):
            self.same(result_refs.get(key), expected, "results", f"/references/{key}")
        generation = self.plan.get("generation", {})
        run = self.results.get("runInfo", {})
        for key in ("bundleId", "scenarioId"):
            self.same(generation.get(key), self.requirements.get("bundleGeneration", {}).get(key),
                      "plan", f"/generation/{key}")
            self.same(run.get(key), generation.get(key), "results", f"/runInfo/{key}")
        self.reference(self.selected_sut, self.suts, "plan", "/sutId")
        self.reference(run.get("sutId"), self.suts, "results", "/runInfo/sutId")
        self.same(run.get("sutId"), self.selected_sut, "results", "/runInfo/sutId")

    def local_indexes(self) -> None:
        pairs = set()
        for position, api in enumerate(self.requirements.get("templates", [])):
            pair = (api.get("serviceId"), api.get("callId"))
            if None not in pair:
                if pair in pairs:
                    self.issue(_DUPLICATE_ID, "requirements", f"/templates/{position}/callId",
                               "A populated serviceId/callId pair must identify one API template.")
                pairs.add(pair)
        for position, entity in enumerate(self.requirements.get("testData", {}).get("entities", [])):
            path = f"/testData/entities/{position}"
            columns = self.index(entity.get("columns", []), "name", "requirements", f"{path}/columns")
            sources = self.index(entity.get("sources", []), "sutRef", "requirements", f"{path}/sources")
            for source_position, source in enumerate(entity.get("sources", [])):
                self.reference(source.get("sutRef"), self.suts, "requirements",
                               f"{path}/sources/{source_position}/sutRef")
            if entity.get("entityId") is not None:
                self.columns[entity["entityId"]] = columns
                self.sources[entity["entityId"]] = sources
        for position, database in enumerate(self.requirements.get("databases", [])):
            self.reference(database.get("sutRef"), self.suts, "requirements", f"/databases/{position}/sutRef")
            queries = self.index(database.get("sqlQueries", []), "queryId", "requirements",
                                 f"/databases/{position}/sqlQueries")
            if database.get("dbId") is not None:
                self.queries[database["dbId"]] = queries

    def endpoint(self, value: object, document: str, pointer: str, protocol: object = None) -> None:
        selected = self.suts.get(self.selected_sut)
        if value is None or selected is None:
            return
        endpoints = selected[0].get("endpoints", {})
        if value not in endpoints:
            self.issue(_UNKNOWN_REFERENCE, document, pointer,
                       "Endpoint reference does not exist in the explicitly selected SUT.")
        else:
            self.same(protocol, endpoints[value].get("kind"), document, pointer)

    def selected_apis(self) -> None:
        for position, entry in enumerate(self.plan.get("apiExecution", [])):
            self.reference(entry.get("apiRef"), self.apis, "plan", f"/apiExecution/{position}/apiRef")
        for _, entry in active_execution_rows(self.plan):
            target = self.apis.get(entry.get("apiRef"))
            if target is None:
                continue
            api, path = target
            self.endpoint(api.get("endpointRef"), "requirements", f"{path}/endpointRef", api.get("protocol"))
            token_source = api.get("authorization", {}).get("tokenSource")
            if token_source is not None:
                self.reference(token_source.get("apiRef"), self.apis, "requirements",
                               f"{path}/authorization/tokenSource/apiRef")
            for position, binding in enumerate(api.get("payloadBindings", [])):
                source = binding.get("source", {})
                pointer = f"{path}/payloadBindings/{position}/source"
                entity_id = source.get("entityRef")
                entity = self.reference(entity_id, self.entities, "requirements", f"{pointer}/entityRef")
                if entity is not None:
                    self.used_entities.add(entity_id)
                    self.reference(source.get("column"), self.columns[entity_id], "requirements", f"{pointer}/column")

    def token_dependencies(self) -> None:
        edges = {}
        for identity, target in self.apis.items():
            if target is None:
                continue
            source = target[0].get("authorization", {}).get("tokenSource")
            if source is not None and self.apis.get(source.get("apiRef")) is not None:
                edges[identity] = source["apiRef"]
        complete = set()
        for start in edges:
            if start in complete:
                continue
            positions = {}
            path = []
            current = start
            while current in edges and current not in complete and current not in positions:
                positions[current] = len(path)
                path.append(current)
                current = edges[current]
            if current in positions:
                for identity in path[positions[current]:]:
                    self.issue(_TOKEN_DEPENDENCY_CYCLE, "requirements",
                               f"{self.apis[identity][1]}/authorization/tokenSource/apiRef",
                               "Explicit token dependencies must not contain a cycle.")
            complete.update(path)

    def database_reference(self, row: dict, document: str, path: str, sut: object) -> None:
        database_id = row.get("databaseRef")
        database = self.reference(database_id, self.databases, document, f"{path}/databaseRef")
        if database is None:
            return
        self.same(database[0].get("sutRef"), sut, document, f"{path}/databaseRef")
        self.reference(row.get("queryRef"), self.queries[database_id], document, f"{path}/queryRef")

    def data_rows(self, rows: list, document: str, path: str) -> None:
        for position, row in enumerate(rows):
            pointer = f"{path}/{position}"
            entity_id = row.get("entityRef")
            entity = self.reference(entity_id, self.entities, document, f"{pointer}/entityRef")
            self.reference(row.get("sourceSutRef"), self.suts, document, f"{pointer}/sourceSutRef")
            self.same(row.get("sourceSutRef"), self.selected_sut, document, f"{pointer}/sourceSutRef")
            if entity is None:
                continue
            self.used_entities.add(entity_id)
            source = self.reference(row.get("sourceSutRef"), self.sources[entity_id], document,
                                    f"{pointer}/sourceSutRef")
            if source is not None:
                self.same(row.get("sourceType"), source[0].get("type"), document, f"{pointer}/sourceType")

    def selected_data(self) -> None:
        self.data_rows(self.plan.get("dataPreparation", []), "plan", "/dataPreparation")
        self.data_rows(self.results.get("dataObservations", []), "results", "/dataObservations")
        if self.selected_sut is None or self.suts.get(self.selected_sut) is None:
            return
        for entity_id in sorted(self.used_entities):
            source = self.sources[entity_id].get(self.selected_sut)
            if source is not None:
                self.database_reference(source[0], "requirements", source[1], self.selected_sut)
        for position, row in enumerate(self.results.get("sqlChecks", [])):
            path = f"/sqlChecks/{position}"
            self.reference(row.get("sutRef"), self.suts, "results", f"{path}/sutRef")
            self.same(row.get("sutRef"), self.selected_sut, "results", f"{path}/sutRef")
            self.database_reference(row, "results", path, self.selected_sut)

    def criterion_references(self) -> None:
        for position, kpi in enumerate(self.requirements.get("kpis", [])):
            path = f"/kpis/{position}"
            self.reference(kpi.get("criterionRef"), self.criteria, "requirements", f"{path}/criterionRef")
            self.reference(kpi.get("apiRef"), self.apis, "requirements", f"{path}/apiRef")
            self.reference(kpi.get("sutRef"), self.suts, "requirements", f"{path}/sutRef")
        for position, criterion in enumerate(self.plan.get("acceptanceCriteria", [])):
            path = f"/acceptanceCriteria/{position}"
            self.reference(criterion.get("criterionRef"), self.criteria, "plan", f"{path}/criterionRef")
            for rule_position, rule in enumerate(criterion.get("measurableRules", [])):
                pointer = f"{path}/measurableRules/{rule_position}"
                self.reference(rule.get("apiRef"), self.apis, "plan", f"{pointer}/apiRef")
                self.reference(rule.get("sutRef"), self.suts, "plan", f"{pointer}/sutRef")
                self.same(rule.get("sutRef"), self.selected_sut, "plan", f"{pointer}/sutRef")

    def sequence(self, rows: list, document: str, path: str) -> None:
        steps = self.index(rows, "stepId", document, path)
        for position, row in enumerate(rows):
            pointer = f"{path}/{position}"
            self.reference(row.get("apiRef"), self.apis, document, f"{pointer}/apiRef")
            for correlation_position, correlation in enumerate(row.get("correlations", [])):
                correlation_path = f"{pointer}/correlations/{correlation_position}"
                for key in ("fromStepRef", "toStepRef"):
                    self.reference(correlation.get(key), steps, document, f"{correlation_path}/{key}")

    def result_references(self) -> None:
        api_rows = self.results.get("apiResults", [])
        criterion_rows = self.results.get("criterionResults", [])
        self.index(api_rows, "apiRef", "results", "/apiResults")
        self.index(criterion_rows, "criterionRef", "results", "/criterionResults")
        for position, row in enumerate(api_rows):
            self.reference(row.get("apiRef"), self.plan_apis, "results", f"/apiResults/{position}/apiRef")
        for position, row in enumerate(criterion_rows):
            self.reference(row.get("criterionRef"), self.plan_criteria, "results", f"/criterionResults/{position}/criterionRef")
        monitors = self.index(self.results.get("monitoring", {}).get("sources", []),
                              "monitorId", "results", "/monitoring/sources")
        for position, evidence in enumerate(self.results.get("monitoring", {}).get("evidence", [])):
            self.reference(evidence.get("monitorRef"), monitors, "results", f"/monitoring/evidence/{position}/monitorRef")

    def document_pointer(self, value: object, target_document: str | None, pointer: str) -> None:
        if value is None or target_document is None:
            return
        try:
            resolve(self.documents[target_document], value)
        except IntakeError:
            self.issue(_UNKNOWN_REFERENCE, "traceability", pointer,
                       "Exact JSON Pointer does not resolve in its declared document.")

    def ledger_references(self) -> None:
        instance = self.documents["traceability"].get("instance", {})
        for collection in ("questions", "proposals"):
            rows = instance.get(collection, [])
            path = f"/instance/{collection}"
            self.index(rows, "id", "traceability", path)
            for position, row in enumerate(rows):
                for target_position, target in enumerate(row.get("targets", [])):
                    pointer = f"{path}/{position}/targets/{target_position}/pointer"
                    self.document_pointer(target.get("pointer"), target.get("document"), pointer)
        for position, row in enumerate(instance.get("coverage", [])):
            path = f"/instance/coverage/{position}"
            for key, document in (("kpiPointer", "requirements"), ("planRulePointer", "plan"),
                                  ("resultPointer", "results")):
                self.document_pointer(row.get(key), document, f"{path}/{key}")
            for key, indexed in (("criterionRef", self.criteria), ("apiRef", self.apis),
                                 ("sutRef", self.suts)):
                self.reference(row.get(key), indexed, "traceability", f"{path}/{key}")

    def run(self) -> list[dict]:
        self.identities()
        self.local_indexes()
        self.selected_apis()
        self.token_dependencies()
        self.selected_data()
        self.criterion_references()
        self.sequence(self.plan.get("executionModel", {}).get("sequence", []), "plan", "/executionModel/sequence")
        self.sequence(self.results.get("actualRunConfig", {}).get("sequence", []), "results", "/actualRunConfig/sequence")
        self.result_references()
        self.ledger_references()
        for document, rows, path in (
            ("plan", self.plan.get("mockSetup", {}).get("dependencies", []), "/mockSetup/dependencies"),
            ("results", self.results.get("dependencyObservations", []), "/dependencyObservations"),
        ):
            for position, row in enumerate(rows):
                self.endpoint(row.get("endpointRef"), document, f"{path}/{position}/endpointRef")
        return self.issues


def check_references(docs: dict) -> list[dict]:
    """Return reference issues after the four canonical schemas have passed."""
    return _References(docs).run()
