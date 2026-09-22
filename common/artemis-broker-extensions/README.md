# Artemis broker extensions

This Java 21 JAR contains `DiagnosticCaptureTransformer`, the broker-side part of
PocketHive's bounded diagnostic capture. It clears scheduled delivery on the copy
created by the non-exclusive divert. It leaves source delivery, WorkItem bytes,
expiry and other properties unchanged.

The `artemis` image installs this JAR in the Artemis runtime library directory.
`artemis-adapter` references its explicit class name when creating a tap; a missing
extension fails tap creation. Worker and service runtime classpaths do not need the
extension. The adapter's embedded-broker tests use it as a test dependency.

Artemis 2.40.0 documents that [scheduled messages bypass ring queue limits](https://artemis.apache.org/components/artemis/documentation/previous/2.40.0/ring-queues.html#scheduled-messages).
The implementation uses the broker's [supported message scheduling API](https://github.com/apache/activemq-artemis/blob/2.40.0/artemis-core-client/src/main/java/org/apache/activemq/artemis/core/message/impl/CoreMessage.java)
to remove the schedule from the copy. It does not encode an invalid schedule value
or introduce a broker polling loop.

The ownership contract is [RESP-ARTEMIS-CAPTURE-TRANSFORM](../../docs/architecture/runtime-responsibilities.md#resp-artemis-capture-transform).
Run the module behavior and embedded-broker regressions from the repository root:

```bash
./mvnw -pl common/artemis-adapter -am test
```
