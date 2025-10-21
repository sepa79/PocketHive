# PocketHive Worker Starter (in-repo template)

This directory provides a **copy-friendly starting point** for building custom PocketHive workers. The
multi-module project ships two runnable examples—a generator and a processor—so teams can study both
entry-point and mid-pipeline worker patterns. Keep the template inside the main repo so it always tracks the
latest Worker SDK contracts, then copy it into your own repository when starting a new worker.

## Layout

```
examples/worker-starter/
├── README.md
├── pom.xml                         # parent POM wiring generator & processor modules
├── generator-worker/               # sample generator worker implementation
│   ├── docker/Dockerfile
│   ├── pom.xml
│   └── src/
├── processor-worker/               # sample processor (message) worker implementation
│   ├── docker/Dockerfile
│   ├── pom.xml
│   └── src/
└── scripts/
    ├── build-image.ps1             # Windows helper to package both images
    └── build-image.sh              # macOS/Linux helper to package both images
```

Each module contains:

- `GeneratorWorkerApplication` / `ProcessorWorkerApplication` bootstrapping Spring Boot and the Worker SDK.
- `SampleGeneratorWorker` / `SampleProcessorWorker` illustrating Stage 1 contracts (`GeneratorWorker` and
  `MessageWorker`).
- Runtime adapters that show how to bridge the worker definition to RabbitMQ while listening for control-plane
  updates.
- Minimal configuration defaults (`application.yml`) that bind the control-plane exchange, traffic exchange, swarm identity, and queue
  aliases used by the workers, plus focused unit tests exercising the behaviour.

## Build & package locally

The helper scripts compile both modules and build Docker images for the generator and processor examples.
Provide explicit image tags for each worker:

```bash
# macOS/Linux
./scripts/build-image.sh my-org/gen:local my-org/proc:local
```

```powershell
# Windows PowerShell
./scripts/build-image.ps1 -GeneratorImageName my-org/gen:local -ProcessorImageName my-org/proc:local
```

Use `--help` (Bash) or `-Help` (PowerShell) for a complete description of the available arguments. Set the
environment variable `SKIP_TESTS=true` (Bash) or pass `-SkipTests` (PowerShell) to skip Maven tests during the
build. When Maven (`mvn` or the wrapper) is available locally the scripts first install the shared PocketHive
artifacts (parent POM + Worker SDK) into your local `~/.m2`, then compile the generator and processor modules.
If Maven isn’t available the multi-stage Dockerfiles still take care of compilation inside the build container.

## Copy checklist

When creating your own workers from this template:

- [ ] Copy the entire `examples/worker-starter` directory to a new repository.
- [ ] Update Maven coordinates (`groupId`, `artifactId`, version) in the parent and module POMs.
- [ ] Rename packages under `io.pockethive.examples.starter.generator` and `.processor` to match your namespace.
- [ ] Adjust the `@PocketHiveWorker` annotations (role, queue aliases, config classes) to reflect your topology.
- [ ] Align `pockethive.control-plane.*` defaults in each module’s `application.yml` (control-plane exchange, traffic exchange, swarm identity, queue
      aliases) with the values provisioned by your Swarm Controller.
- [ ] Replace the sample business logic and tests with your own, keeping the runtime adapters as references.
- [ ] Update the build scripts or Dockerfiles if you change module names or image packaging conventions.

After copying, replace this README with project-specific documentation tailored to your workers.
