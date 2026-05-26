#!/usr/bin/env python3
"""Render Docker Compose JSON into a Docker Stack compatible YAML file."""

from __future__ import annotations

import json
import shlex
import sys
from typing import Any

import yaml


def main() -> int:
    config = json.load(sys.stdin)
    normalize_stack_config(config)
    print(yaml.safe_dump(config, sort_keys=False), end="")
    return 0


def normalize_stack_config(config: dict[str, Any]) -> None:
    config.pop("name", None)
    strip_generated_resource_names(config.get("networks"))
    strip_generated_resource_names(config.get("volumes"))

    for service in config.get("services", {}).values():
        if not isinstance(service, dict):
            continue
        service.pop("depends_on", None)
        stringify_entrypoint(service)
        normalize_ports(service)

    remove_nulls(config)


def strip_generated_resource_names(resources: Any) -> None:
    if not isinstance(resources, dict):
        return

    for resource in resources.values():
        if isinstance(resource, dict) and not resource.get("external"):
            resource.pop("name", None)


def stringify_entrypoint(service: dict[str, Any]) -> None:
    entrypoint = service.get("entrypoint")
    if isinstance(entrypoint, list):
        service["entrypoint"] = shlex.join(str(item) for item in entrypoint)


def normalize_ports(service: dict[str, Any]) -> None:
    ports = service.get("ports")
    if not isinstance(ports, list):
        return

    for port in ports:
        if not isinstance(port, dict):
            continue
        for key in ("target", "published"):
            value = port.get(key)
            if isinstance(value, str) and value.isdecimal():
                port[key] = int(value)


def remove_nulls(value: Any) -> Any:
    if isinstance(value, dict):
        for key in list(value.keys()):
            if value[key] is None:
                del value[key]
            else:
                remove_nulls(value[key])
    elif isinstance(value, list):
        for item in value:
            remove_nulls(item)
    return value


if __name__ == "__main__":
    raise SystemExit(main())
