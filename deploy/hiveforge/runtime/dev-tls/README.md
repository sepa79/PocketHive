# Public test TLS identity

This certificate and private key are intentionally public test fixtures for the
PocketHive development Swarm at 192.168.88.50–53. They provide HTTPS transport for
the current MCP contract, not a confidential or production identity. Do not reuse
this key outside the test environment. Clients must explicitly trust server.crt;
do not disable certificate validation. Renew this fixture before its expiry.
