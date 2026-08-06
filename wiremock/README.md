WireMock mappings for NFT tests.

Mount stub definitions under `mappings/` and static responses in `__files/`.
Request journaling is enabled and bounded by
`--max-request-journal-entries=1000`; the Compose file leaves the optional
`--no-request-journal` setting commented out. The container listens on port
8080 inside the Compose network. Customer access uses the official ingress at
`http://localhost:8088/wiremock/`, not the direct backend port.
