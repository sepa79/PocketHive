"""Responsibility: dispatch intake operations to their single owning components.
Must not: duplicate parsers, validators or projection logic. Contract: intake-contract.md.
"""
from .bundle_inspector import BundleInspector
from .document_store import DocumentStore
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
    root = package.workspace(args.documents, write=args.command == "finalise")
    docs = store.load(root)
    validation = Validation(package, codec, store)
    if args.command == "validate":
        return validation.check(root, docs)
    errors = validation.structure(docs)
    if errors:
        return {"errors": errors}
    return Projections(package, codec, store).save(root, docs)
