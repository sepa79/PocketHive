FROM apache/activemq-artemis:2.40.0

# Broker distribution libraries remain visible when an existing instance volume is mounted.
COPY --chown=artemis:artemis .local-jars/common/artemis-broker-extensions.jar /opt/activemq-artemis/lib/pockethive-artemis-broker-extensions.jar
