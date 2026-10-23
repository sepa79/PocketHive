package io.pockethive.auth.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.pockethive.auth.contract.PublicEndpointTransportPolicy;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Responsibility: Construct the one shared hardened auth HTTP/JSON boundary.
 * Must not: Cache tokens, infer endpoints or decide product access.
 * Contract: RESP-POCKET-AUTH-HTTP — docs/architecture/runtime-responsibilities.md#resp-pocket-auth-http.
 */
public final class AuthServiceRestClients {
    private AuthServiceRestClients() {}

    public static RestClient create(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
        if (!PublicEndpointTransportPolicy.allows(baseUrl, true)) {
            throw new IllegalArgumentException("Auth base URL must be an explicit HTTP(S) endpoint");
        }
        var mapper = JsonMapper.builder().findAndAddModules()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        return RestClient.builder().baseUrl(baseUrl.toString().replaceAll("/+$", ""))
            .requestFactory(AuthServiceRequestFactory.create(connectTimeout, readTimeout))
            .requestInterceptor((request, body, execution) -> {
                long deadline = System.nanoTime() + readTimeout.toNanos();
                return new BoundedAuthResponse(execution.execute(request, body), deadline);
            })
            .messageConverters(converters -> {
                converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                converters.add(new MappingJackson2HttpMessageConverter(mapper));
            })
            .defaultStatusHandler(status -> status.is3xxRedirection(), (request, response) -> {
                throw new AuthServiceClientException("Auth redirects are not allowed", 503, null);
            })
            .build();
    }
}
