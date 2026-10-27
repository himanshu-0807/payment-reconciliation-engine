package dev.recon.service.ops;

import dev.recon.service.api.ConflictException;
import dev.recon.service.api.UpstreamException;
import dev.recon.service.config.ReconProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/** Thin proxy to gateway-sim's control API, so the dashboard only talks to recon-service. */
@Component
public class SimulatorClient {

    private final RestClient rest;

    public SimulatorClient(RestClient.Builder builder, ReconProperties props) {
        this.rest = builder.baseUrl(props.gatewaySimUrl()).build();
    }

    public Map<String, Object> start(Map<String, Object> body) {
        return call(() -> rest.post().uri("/sim/runs").body(body).retrieve().body(MAP));
    }

    public Map<String, Object> run(String id) {
        return call(() -> rest.get().uri("/sim/runs/{id}", id).retrieve().body(MAP));
    }

    public List<Map<String, Object>> runs() {
        return call(() -> rest.get().uri("/sim/runs").retrieve().body(LIST));
    }

    public Map<String, Object> defaults() {
        return call(() -> rest.get().uri("/sim/defaults").retrieve().body(MAP));
    }

    private static <T> T call(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new ConflictException("SIMULATION_RUNNING", e.getResponseBodyAsString());
            }
            if (e.getStatusCode().value() == 400) {
                throw new IllegalArgumentException("simulator rejected the request: " + e.getResponseBodyAsString());
            }
            throw new UpstreamException("gateway-sim returned " + e.getStatusCode().value() + ": "
                    + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new UpstreamException("gateway-sim unreachable: " + e.getMessage(), e);
        }
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {
    };
}
