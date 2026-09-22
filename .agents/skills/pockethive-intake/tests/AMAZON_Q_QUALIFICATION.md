# Amazon Q and sandbox qualification — 2026-09-16

**The package passes offline sandbox checks. Amazon Q conversation support is
unqualified because no account was available.** A sandbox isolates execution;
it does not provide an Amazon Q model or replace authentication.

## Tested versions and artifact

| Component | Observed version |
| --- | --- |
| VS Code, Linux x64 | 1.137.0 |
| `amazonwebservices.amazon-q-vscode` | 2.7.0 |
| Amazon Q CLI | 1.19.7 |
| Python | 3.12.3 |
| Intake package | 1.0.0; 286,598-byte archive |

Tested archive SHA-256:
`e5d6e7ad9e7b6d0c633575d7a718c03672b744f66a92ad51e29242a4139a4f73`.
This identifies the archive tested before adding these qualification notes.
The release checksum sidecar identifies the subsequently packaged documentation.

The explicitly authorised CLI installation used the pinned
[official Q 1.19.7 release](https://github.com/aws/amazon-q-developer-cli/releases/tag/v1.19.7).
No shell startup files or existing agent/MCP configuration were changed.

## Actual Amazon Q checks

| Check | Observed result | What remains unproven |
| --- | --- | --- |
| Installed VS Code extension | `Amazon Q: Open Chat` opened its sign-in pane in a new synthetic workspace. | Authenticated chat, rule application, file loading, tools and conversation resume. |
| IDE bridge files | The scoped Markdown bridge exists under `.amazonq/rules/`; its canonical `SKILL.md` target exists in the extracted package. | Whether Q includes and follows those files in model context. |
| CLI `q --version` and help | Version/help commands completed. | Model or agent behaviour. |
| CLI `q agent validate --path ...` | Exit 1: `You are not logged in, please log in with q login`. | Configuration acceptance and resource loading. |
| CLI `q agent list` | Exit 1 with the same sign-in requirement. | Named-agent discovery and selection. |
| Malformed-agent and missing-resource controls | Both reached the same authentication gate. | Rejection of the underlying configuration defects. |

The CLI commands ran with external networking disabled and the host home
directory masked. The installed help exposed no offline-model or built-in
sandbox option. No Q model call, login, copied credentials, replacement model
backend or authentication bypass was used. The native IDE check was outside the
OS sandbox; only its new synthetic workspace was marked trusted.

AWS documents the [project-rule location and session toggles](https://docs.aws.amazon.com/amazonq/latest/qdeveloper-ug/context-project-rules.html)
and [IDE authentication requirement](https://docs.aws.amazon.com/amazonq/latest/qdeveloper-ug/q-in-IDE-setup.html).
Those documents support integration guidance, not a claim that this skill has
passed an authenticated Q test.

## Package execution inside an OS sandbox

Bubblewrap created separate namespaces, private temporary storage and a
read-only mount of the extracted skill. The host repository and home directory
were absent. The Python process had no inherited application environment,
`PYTHONPATH` or site packages. A probe observed only loopback networking and
external connection error 101 (`ENETUNREACH`); package writes were unavailable.

The public Python suite passed **54 tests in 79.358 seconds**, exit 0. This
includes both intake modes, incomplete handoffs, source integrity, malicious
YAML rejection, missing assets, evidence validation and relocated ZIP checks.
It tests the package CLI; it is not an Amazon Q conversation test.

On the tested Linux layout, replace the explicit package path below to reproduce
the isolated suite. Bubblewrap is an optional test tool, not a skill runtime
dependency. Do not describe an ordinary process run as sandboxed if namespace
creation fails.

```sh
bwrap --unshare-all --die-with-parent --new-session \
  --ro-bind /usr /usr \
  --symlink usr/lib /lib --symlink usr/lib64 /lib64 --symlink usr/bin /bin \
  --proc /proc --dev /dev --tmpfs /tmp \
  --ro-bind "/absolute/path/to/pockethive-intake" /package/.agents/skills/pockethive-intake \
  --chdir /package/.agents/skills/pockethive-intake \
  --clearenv --setenv PATH /usr/bin --setenv LANG C.UTF-8 \
  /usr/bin/python3 -B -I -S -m unittest discover -s tests -v
```

## Separate synthetic intake rehearsal

A Codex agent explicitly loaded the same extracted skill and completed a
new-requirements intake in a separate temporary authoring workspace. This was
an agent-authored rehearsal, outside the OS sandbox, not Amazon Q execution or
native client discovery.

The synthetic brief supplied an account-status HTTP read journey and reported
an illustrative 100 requests/second configuration. Four YAML documents were
saved with provenance and three material questions. Numeric acceptance targets
remained unknown; the reported rate stayed an observation.

A labelled synthetic reply supplied only the SUT ID `qa-account-status`.
Resuming the existing documents preserved administrative IDs, answered only
the SUT question and retained the other two questions. The proposed approach
remained unaccepted, review remained unconfirmed and results remained
unexecuted without achieved measurements.

Both initial and resumed drafts returned exit 0, `incomplete`, with no errors.
Handoff returned exit 3, `incomplete`, with no errors. The detailed validation
retained 77 initial and 78 resumed gaps; these were not presented as dozens of
interview questions. The stakeholder review summarised purpose, scope, proposed
approach and the two remaining decision groups. No package defect was observed
in this bounded rehearsal; it does not establish every client or scenario.

## Evidence and remaining gates

Raw local commands, outputs, sandbox probes and the IDE sign-in screenshot were
retained under `/tmp/pockethive-q-qualification-20260916/` in the originating
workspace. These paths are local evidence, not package dependencies. The user
confirmed that no Q account was available; authentication-dependent checks are
pending, not passed or replaced by package tests.

An authenticated test must still demonstrate actual canonical-file loading,
saved drafts, material questions, source faithfulness, tool failure handling and
conversation resume separately for each Q surface. Other requested clients keep
their own qualification gates.
