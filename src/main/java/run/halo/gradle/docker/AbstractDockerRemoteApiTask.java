package run.halo.gradle.docker;

import com.github.dockerjava.api.DockerClient;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * @author guqing
 * @since 2.0.0
 */
public abstract class AbstractDockerRemoteApiTask extends DefaultTask {

    /**
     * Docker remote API server URL. Defaults to "http://localhost:2375".
     */
    @Input
    @Optional
    public final Property<String> getUrl() {
        return url;
    }

    private final Property<String> url = getProject().getObjects().property(String.class);

    /**
     * Path to the
     * <a href="https://docs.docker.com/engine/security/https/">Docker certificate and key</a>.
     */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    public final DirectoryProperty getCertPath() {
        return certPath;
    }

    private final DirectoryProperty certPath = getProject().getObjects().directoryProperty();

    /**
     * The Docker remote API version.
     */
    @Input
    @Optional
    public final Property<String> getApiVersion() {
        return apiVersion;
    }

    private final Property<String> apiVersion = getProject().getObjects().property(String.class);

    @Internal
    public final Property<DockerClientService> getDockerClientService() {
        return dockerClientService;
    }

    private final Property<DockerClientService> dockerClientService =
        getProject().getObjects().property(DockerClientService.class);

    private Action<? super Throwable> errorHandler;
    private Action<? super Object> nextHandler;
    private Runnable completeHandler;

    @TaskAction
    public void start() throws Exception {
        boolean commandFailed = false;
        try {
            runRemoteCommand();
        } catch (Exception possibleException) {
            commandFailed = true;
            if (errorHandler != null) {
                errorHandler.execute(possibleException);
            } else {
                throw possibleException;
            }
        }

        if (!commandFailed && completeHandler != null) {
            completeHandler.run();
        }
    }

    /**
     * Reacts to a potential error occurring during the operation.
     *
     * @param action The action handling the error
     * @since 4.0.0
     */
    public void onError(Action<? super Throwable> action) {
        errorHandler = action;
    }

    /**
     * Reacts to data returned by an operation.
     *
     * @param action The action handling the data
     * @since 4.0.0
     */
    public void onNext(Action<? super Object> action) {
        nextHandler = action;
    }

    @Internal
    protected Action<? super Object> getNextHandler() {
        return nextHandler;
    }

    /**
     * Reacts to the completion of the operation.
     *
     * @param callback The callback to be executed
     * @since 4.0.0
     */
    public void onComplete(Runnable callback) {
        completeHandler = callback;
    }

    /**
     * Gets the Docker client uses to communicate with Docker via its remote API.
     * Initialized instance upon first request.
     * Returns the same instance for any successive method call.
     * To support the configuration cache we rely on DockerClientService's internal cache.
     * <p>
     * Before accessing the Docker client, all data used for configuring its runtime behavior
     * needs to be evaluated.
     * The data includes:
     * <ol>
     * <li>The property values of this class</li>
     * <li>The plugin's extension property values</li>
     * </ol>
     * <p>
     * It is safe to access the Docker client under the following conditions:
     * <ol>
     * <li>In the task action</li>
     * <li>In the task's constructor if used in {@code Action} or {@code Closure} of {@code
     * outputs.upToDateWhen}</li>
     * </ol>
     *
     * @return The Docker client
     */
    @Internal
    public DockerClient getDockerClient() {
        return dockerClientService.get().getDockerClient(createDockerClientConfig());
    }

    private DockerClientConfiguration createDockerClientConfig() {
        DockerClientConfiguration dockerClientConfig = new DockerClientConfiguration();
        dockerClientConfig.setUrl(url.getOrNull());
        dockerClientConfig.setCertPath(certPath.getOrNull());
        dockerClientConfig.setApiVersion(apiVersion.getOrNull());
        return dockerClientConfig;
    }

    public abstract void runRemoteCommand() throws Exception;
}
