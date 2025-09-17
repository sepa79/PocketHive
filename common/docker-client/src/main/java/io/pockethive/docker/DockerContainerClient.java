package io.pockethive.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.HostConfig;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

public class DockerContainerClient {
    private static final String DOCKER_HINT =
        "Ensure Docker is installed, running, and that the process can access the Docker socket "
            + "(for example /var/run/docker.sock) or an explicit DOCKER_HOST.";

    private final DockerClient dockerClient;

    public DockerContainerClient(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public String createAndStartContainer(String image, Map<String, String> env) {
        return createAndStartContainer(image, env, null);
    }

    public String createAndStartContainer(String image, Map<String, String> env, String containerName) {
        String id = createContainer(image, env, containerName);
        startContainer(id);
        return id;
    }

    public String createAndStartContainer(String image) {
        return createAndStartContainer(image, Map.of(), null);
    }

    public String createAndStartContainer(String image, String containerName) {
        return createAndStartContainer(image, Map.of(), containerName);
    }

    public String createContainer(String image, Map<String, String> env) {
        return createContainer(image, env, null);
    }

    public String createContainer(String image, Map<String, String> env, String containerName) {
        return callDocker("create container", () -> {
            String[] envArray = toEnvArray(env);
            CreateContainerCmd createCmd = dockerClient.createContainerCmd(image)
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(resolveControlNetwork()))
                .withEnv(envArray);
            if (containerName != null && !containerName.isBlank()) {
                createCmd = createCmd.withName(containerName);
            }
            CreateContainerResponse response = createCmd.exec();
            return response.getId();
        });
    }

    public String createContainer(String image) {
        return createContainer(image, Map.of(), null);
    }

    public String createContainer(String image, String containerName) {
        return createContainer(image, Map.of(), containerName);
    }

    public void startContainer(String containerId) {
        callDocker("start container", () -> dockerClient.startContainerCmd(containerId).exec());
    }

    public void stopAndRemoveContainer(String containerId) {
        callDocker("stop container", () -> dockerClient.stopContainerCmd(containerId).exec());
        callDocker("remove container", () -> dockerClient.removeContainerCmd(containerId).exec());
    }

    public String resolveControlNetwork() {
        String net = System.getenv("CONTROL_NETWORK");
        if (net == null || net.isBlank()) {
            try {
                String self = System.getenv("HOSTNAME");
                if (self != null) {
                    InspectContainerResponse inspect = dockerClient.inspectContainerCmd(self).exec();
                    net = inspect.getNetworkSettings().getNetworks().keySet().stream()
                        .filter(n -> !"bridge".equals(n))
                        .findFirst().orElse(null);
                }
            } catch (Exception ignored) {
            }
        }
        return net;
    }

    private String[] toEnvArray(Map<String, String> env) {
        return env.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .toArray(String[]::new);
    }

    private <T> T callDocker(String action, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw translate(action, e);
        }
    }

    private void callDocker(String action, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            throw translate(action, e);
        }
    }

    private RuntimeException translate(String action, RuntimeException e) {
        if (e instanceof DockerDaemonUnavailableException) {
            return e;
        }
        if (isDockerUnavailable(e)) {
            return new DockerDaemonUnavailableException(
                "Unable to " + action + " because the Docker daemon is unavailable. " + DOCKER_HINT,
                e);
        }
        return e;
    }

    private boolean isDockerUnavailable(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof DockerDaemonUnavailableException
                || t instanceof ConnectException
                || t instanceof NoRouteToHostException
                || t instanceof SocketTimeoutException
                || t instanceof UnknownHostException
                || t instanceof FileNotFoundException
                || t instanceof NoSuchFileException
                || (t instanceof IOException && messageContains(t, "No such file or directory"))) {
                return true;
            }
            String className = t.getClass().getName();
            if ("com.sun.jna.LastErrorException".equals(className)
                && messageContains(t, "No such file or directory")) {
                return true;
            }
            if (messageContains(t, "Could not find a valid Docker environment")) {
                return true;
            }
            if (messageContains(t, "permission denied") && messageContains(t, "docker")) {
                return true;
            }
        }
        return false;
    }

    private boolean messageContains(Throwable t, String needle) {
        if (needle == null || needle.isBlank()) {
            return false;
        }
        String message = t.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT)
            .contains(needle.toLowerCase(Locale.ROOT));
    }
}
