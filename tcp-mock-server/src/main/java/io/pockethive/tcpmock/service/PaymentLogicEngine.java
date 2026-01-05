package io.pockethive.tcpmock.service;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Random;

@Service
public class PaymentLogicEngine {
    private final Random random = new Random();

    public PaymentResponse processPayment(PaymentRequest request) {
        if (request.getAmount().compareTo(BigDecimal.valueOf(10000)) > 0) {
            return new PaymentResponse("05", null, "Do not honor");
        }

        String authCode = String.format("%06d", random.nextInt(999999));
        return new PaymentResponse("00", authCode, "Approved");
    }

    public static class PaymentRequest {
        private final String pan;
        private final BigDecimal amount;
        private final String scenario;

        public PaymentRequest(String pan, BigDecimal amount, String scenario) {
            this.pan = pan;
            this.amount = amount;
            this.scenario = scenario;
        }

        public String getPan() { return pan; }
        public BigDecimal getAmount() { return amount; }
        public String getScenario() { return scenario; }
    }

    public static class PaymentResponse {
        private final String responseCode;
        private final String authCode;
        private final String message;

        public PaymentResponse(String responseCode, String authCode, String message) {
            this.responseCode = responseCode;
            this.authCode = authCode;
            this.message = message;
        }

        public String getResponseCode() { return responseCode; }
        public String getAuthCode() { return authCode; }
        public String getMessage() { return message; }
    }
}
