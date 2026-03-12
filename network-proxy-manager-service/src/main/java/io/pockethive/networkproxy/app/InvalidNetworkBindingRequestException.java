package io.pockethive.networkproxy.app;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidNetworkBindingRequestException extends IllegalArgumentException {

    InvalidNetworkBindingRequestException(String message) {
        super(message);
    }
}
