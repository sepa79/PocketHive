# Deployment package regression tests

This directory contains maintained regression tests for the root
[`package-deployment.sh`](../../../package-deployment.sh), not generated test
results or deployed scenarios. They check that the deployment archive contains
the required runtime assets, renders the supported Compose path layouts, excludes
secrets and application sources, and fails when required inputs are missing or
have the wrong file type.

## Run

From the repository root:

```sh
python3 -m unittest discover -s tools/deployment-package/tests -v
```

Requirements: Python 3, Git, Bash, standard archive tools (including `tar`), and
Docker with Docker Compose. The suite uses Python's standard library; no Python
packages need to be installed. These checks are currently run manually, not by
the CI workflow.

## What the tests do

[`test_deployment_package.py`](test_deployment_package.py) stages the tracked
repository files in temporary directories, builds and extracts real archives,
and renders Compose configuration. Only Maven's version query is stubbed.
The tests do not start containers, deploy PocketHive, or download images.

Temporary staging directories are intentionally retained for inspection and
their locations are printed by the suite. They are separate from this directory,
which should remain in the repository as test source.

See [Deployment Package — Verify Package Contents](../../../DEPLOYMENT_PACKAGE.md#verify-package-contents)
for the canonical packaging instructions.
