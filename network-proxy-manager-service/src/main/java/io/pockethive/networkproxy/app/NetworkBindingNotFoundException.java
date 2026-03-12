package io.pockethive.networkproxy.app;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class NetworkBindingNotFoundException extends IllegalStateException {

    NetworkBindingNotFoundException(String message) {
        super(message);
    }
}
