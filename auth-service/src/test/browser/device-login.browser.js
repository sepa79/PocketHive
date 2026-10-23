async (page) => {
  const context = await page.context().browser().newContext();
  const issuer = "http://localhost:8089/auth-service";
  const tab = await context.newPage();
  const results = [];
  try {
    for (const client of ["pocketdata-device", "pocketplanner-device"]) {
      const started = await context.request.post(issuer + "/oauth/device_authorization", {
        form: {client_id: client, resource: "urn:pockethive:web", scope: "pockethive:web"},
      });
      if (!started.ok()) throw new Error("Device start failed: " + started.status());
      const device = await started.json();
      if (!device.verification_uri_complete.startsWith(issuer + "/oauth/device_verification?user_code=")) {
        throw new Error("Unexpected PH verification destination");
      }
      await tab.goto(device.verification_uri_complete);
      if (tab.url().includes("/oauth/dev/login")) {
        await tab.locator("#username").fill("local-admin");
        await tab.getByRole("button", {name: "Sign in", exact: true}).click();
      }
      const confirm = tab.getByRole("button", {name: "Confirm sign in", exact: true});
      const complete = tab.getByRole("heading", {name: "Confirmation complete"});
      await confirm.or(complete).first().waitFor();
      if (await confirm.isVisible()) await confirm.click();
      await complete.waitFor();
      const response = await context.request.post(issuer + "/oauth/token", {
        form: {client_id: client, grant_type: "urn:ietf:params:oauth:grant-type:device_code",
          device_code: device.device_code, resource: "urn:pockethive:web"},
      });
      if (!response.ok()) throw new Error("Device token failed: " + response.status());
      const token = await response.json();
      const identity = await context.request.get(issuer + "/api/auth/me", {
        headers: {Authorization: "Bearer " + token.access_token},
      });
      if (identity.status() !== 200 || (await identity.json()).username !== "local-admin") {
        throw new Error("Expected the confirmed PH identity");
      }
      results.push({client, confirmed: true, callbackUsed: false});
    }
    return {scope: "PH server grant, not product-side polling UI", results};
  } finally {
    await context.close();
  }
}
