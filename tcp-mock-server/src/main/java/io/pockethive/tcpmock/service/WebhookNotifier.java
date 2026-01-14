package io.pockethive.tcpmock.service;

import org.springframework.stereotype.Service;

@Service
public class WebhookNotifier {

    public void notifyWebhook(String webhookUrl, String message, String response, String requestId) {
        // Send webhook notification
        System.out.println("Webhook notification sent to: " + webhookUrl);
    }
}
