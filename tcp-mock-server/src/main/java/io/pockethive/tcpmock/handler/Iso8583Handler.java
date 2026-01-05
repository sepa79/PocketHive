package io.pockethive.tcpmock.handler;

import org.springframework.stereotype.Component;

@Component
public class Iso8583Handler {

    public String processIso8583Message(String message) {
        if (message.length() >= 4) {
            String mti = message.substring(0, 4);
            String responseMti = getResponseMti(mti);
            return responseMti + message.substring(4) + "00";
        }
        return "0210" + message + "00";
    }

    private String getResponseMti(String requestMti) {
        if (requestMti.length() == 4) {
            char[] mti = requestMti.toCharArray();
            mti[2] = '1';
            return new String(mti);
        }
        return "0210";
    }
}
