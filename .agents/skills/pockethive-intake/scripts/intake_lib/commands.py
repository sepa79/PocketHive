"""Responsibility: dispatch intake operations to their single owning components.
Must not: duplicate parsers, validators or projection logic. Contract: intake-contract.md.
"""
from contextlib import nullcontext

from .authoring_advisories import authoring_advisories
from .bundle_inspector import BundleInspector
from .document_store import DocumentStore
from .errors import IntakeError
from .initialisation import initialise
from .projections import Projections
from .validation import Validation
from .yaml_codec import YamlCodec


def execute(package, args):
    codec = YamlCodec(package.manifest["limits"])
    store = DocumentStore(package, codec)
    if args.command == "inspect-bundle":
        return BundleInspector(package, codec).inspect(package.workspace(args.source))
    if args.command == "initialise":
        source = package.workspace(args.source) if args.source else None
        return initialise(package, codec, package.workspace(args.output, write=True), args.mode, source)
    writing = args.command in ("finalise", "populate-from-inspection", "apply-updates", "prepare-review")
    root = package.workspace(args.documents, write=writing)
    if not writing:
        store.assert_unlocked(root)
    with store.mutation(root) if writing else nullcontext():
        revision = store.revision(root)
        docs = store.load(root)
        store.assert_revision(root, revision)
        result = _documents(package, codec, store, root, docs, args, revision)
        if not writing:
            store.assert_unlocked(root)
            store.assert_revision(root, revision)
        result.setdefault("documentsSha256", store.revision(root) if writing else revision)
        return result


def _documents(package, codec, store, root, docs, args, revision):
    validation = Validation(package, codec, store)
    if args.command == "validate":
        return validation.check(root, docs)
    errors = validation.structure(docs)
    if errors:
        return {"errors": errors}
    if args.command == "populate-from-inspection":
        from .population import populate_from_inspection
        return populate_from_inspection(package, codec, store, root, docs, expected_revision=revision)
    if args.command == "finalise":
        result = Projections(package, codec, store).save(root, docs, expected_revision=revision)
        result["warnings"] = authoring_advisories(codec.plain(docs))
        return result
    if args.command == "prepare-review":
        from .review_brief import build_brief
        saved = Projections(package, codec, store).save(root, docs, expected_revision=revision)
        docs = store.load(root)
        result = validation.check(root, docs)
        previous = package.workspace(args.previous) if args.previous else None
        brief = build_brief(package, codec, store, root, docs, result, args.stage, previous)
        store.assert_revision(root, saved["documentsSha256"])
        return {**saved, **result, "brief": brief}
    if args.command == "show-field":
        from .field_view import field_view
        if args.document not in package.manifest["templates"]:
            raise IntakeError("DOCUMENT_ROLE", "Use a document role declared by the package manifest.")
        result = validation.check(root, docs)
        view = field_view(package, codec, store, root, docs, result, args.document, args.pointer)
        return view
    if args.command == "apply-updates":
        from .sourced_updates import apply_updates
        return apply_updates(package, codec, store, root, docs, validation, package.workspace(args.input), revision)
    if args.command == "compare-source":
        from .source_comparison import compare_source
        return compare_source(package, codec, store, root, docs, package.workspace(args.previous_source), package.workspace(args.source))
    raise IntakeError("ARGUMENTS", "Unknown intake operation; use --help for the command interface.")
