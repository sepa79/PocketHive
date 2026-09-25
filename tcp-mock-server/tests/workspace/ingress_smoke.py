#!/usr/bin/env python3
"""Verify deployed TCP UI routes retain their public origin (no credentials needed)."""
import argparse
import http.client
import json
from urllib.parse import urljoin, urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ingress", required=True)
    args = parser.parse_args()
    origin = urlsplit(args.ingress)
    assert origin.scheme in ("http", "https") and origin.netloc
    assert origin.path in ("", "/") and not origin.query and not origin.fragment
    connection_type = (http.client.HTTPSConnection if origin.scheme == "https"
                       else http.client.HTTPConnection)

    def get(path):
        connection = connection_type(origin.hostname, origin.port, timeout=10)
        try:
            connection.request("GET", path)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    status, headers, _ = get("/tcp-mock")
    assert status in (301, 302, 307, 308), f"Expected redirect; received {status}"
    location = next(value for key, value in headers.items() if key.lower() == "location")
    target = urlsplit(urljoin(args.ingress + "/", location))
    assert (target.scheme, target.netloc) == (origin.scheme, origin.netloc), "Redirect changed public origin"
    assert target.path == "/tcp-mock/"
    status, _, page = get(target.path)
    assert status == 200 and b'app-ultimate.js' in page
    status, _, config = get("/tcp-mock/api/auth/config")
    assert status == 200
    print(json.dumps({"passed": True, "redirect": location,
                      "provider": json.loads(config)["provider"]}))


if __name__ == "__main__":
    main()
