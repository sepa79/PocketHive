# PocketHive Worker SDK

The Worker SDK packages the Spring Boot auto-configuration and testing helpers required to bootstrap PocketHive control-plane
participants. It combines the shared topology descriptors, emitters, and AMQP infrastructure from the `control-plane-*`
modules so new workers can be scaffolded with minimal ceremony.

## Modules

* `io.pockethive.worker.sdk.autoconfigure.PocketHiveWorkerSdkAutoConfiguration` exposes the canonical control-plane beans for
  worker and manager roles.
* `io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures` provides pre-configured descriptors, identities, and property
  builders that make unit tests easier to wire.

Add the dependency to a worker service to automatically register the control-plane beans:

```xml
<dependency>
  <groupId>io.pockethive</groupId>
  <artifactId>worker-sdk</artifactId>
</dependency>
```
