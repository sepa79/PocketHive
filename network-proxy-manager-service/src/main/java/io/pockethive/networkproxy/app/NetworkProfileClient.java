package io.pockethive.networkproxy.app;

import io.pockethive.swarm.model.NetworkProfile;

public interface NetworkProfileClient {

    NetworkProfile fetch(String profileId) throws Exception;
}
