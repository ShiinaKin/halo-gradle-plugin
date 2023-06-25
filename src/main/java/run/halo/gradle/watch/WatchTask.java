package run.halo.gradle.watch;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.gradle.StartParameter;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.pattern.PatternMatcher;
import org.gradle.api.internal.file.pattern.PatternMatcherFactory;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import run.halo.gradle.HaloExtension;
import run.halo.gradle.HaloPluginExtension;
import run.halo.gradle.WatchExecutionParameters;
import run.halo.gradle.docker.DockerStartContainer;
import run.halo.gradle.steps.CreateHttpClientStep;
import run.halo.gradle.steps.InitializeHaloStep;
import run.halo.gradle.steps.ReloadPluginStep;

/**
 * @author guqing
 * @since 2.0.0
 */
@Slf4j
public class WatchTask extends DockerStartContainer {

    private final HaloPluginExtension pluginExtension =
        getProject().getExtensions().getByType(HaloPluginExtension.class);

    private final HaloExtension haloExtension =
        getProject().getExtensions().getByType(HaloExtension.class);

    final HttpClient httpClient = createHttpClient();

    WatchExecutionParameters getParameters(List<String> buildArgs) {
        return WatchExecutionParameters.builder()
            .projectDir(getProject().getProjectDir())
            .buildArgs(buildArgs)
            .environment(System.getenv())
            .build();
    }

    private ClassPath getInjectedClassPath() {
        StartParameter parameter = getProject().getGradle().getStartParameter();
        String classpath = parameter.getProjectProperties().get("classpath");
        if (classpath != null) {
            List<File> files = Arrays.stream(classpath.split(", "))
                .map(File::new)
                .toList();
            return DefaultClassPath.of(files);
        }
        return null;
    }

    private List<String> getBuildArgs() {
        StartParameter parameter = getProject().getGradle().getStartParameter();
        return getArguments(parameter);
    }

    private List<String> getArguments(StartParameter parameter) {
        List<String> args = new ArrayList<>();
        for (Map.Entry<String, String> e : parameter.getProjectProperties().entrySet()) {
            args.add("-P" + e.getKey() + "=" + e.getValue());
        }
        return args;
    }

    @Override
    public void runRemoteCommand() {
        //Amount of time to wait between polling for classpath changes.
        Duration pollInterval = Duration.ofSeconds(2);
        //Amount of quiet time required without any classpath changes before a restart is triggered.
        Duration quietPeriod = Duration.ofMillis(500);

        FileSystemWatcher watcher = new FileSystemWatcher(false, pollInterval,
            quietPeriod, SnapshotStateRepository.STATIC);
        configWatchFiles(watcher);

        String host = haloExtension.getExternalUrl();
        ReloadPluginStep reloadPluginStep = new ReloadPluginStep(host, httpClient);
        System.out.println("运行........");

        CompletableFuture<Void> initializeFuture = CompletableFuture.runAsync(() -> {
            new InitializeHaloStep(host, httpClient).execute();
            reloadPluginStep.execute(getPluginName());
        });
        initializeFuture.exceptionally(e -> {
            e.printStackTrace();
            return null;
        });

        WatchExecutionParameters parameters = getParameters(List.of("build"));
        try (WatchTaskRunner runner = new WatchTaskRunner(getProject());) {
            watcher.addListener(changeSet -> {
                System.out.println("File changed......" + changeSet);
                runner.run(parameters);
                reloadPluginStep.execute(getPluginName());
            });
            watcher.start();
            // start docker container and waiting
            super.runRemoteCommand();
        }
    }

    private void configWatchFiles(FileSystemWatcher watcher) {
        List<WatchTarget> watchTargets = new ArrayList<>(pluginExtension.getWatchDomains());
        Set<File> watchFiles = new HashSet<>();
        Set<String> excludes = new HashSet<>();
        if (watchTargets.isEmpty()) {
            WatchTarget watchTarget = new WatchTarget("javaSource");
            watchTarget.files(getProject().files("src/main/"));
            watchTarget.excludes("**/src/main/resources/console/**");
            watchTargets.add(watchTarget);
        }
        for (WatchTarget watchTarget : watchTargets) {
            Set<File> files = watchTarget.getFiles().stream()
                .map(FileCollection::getFiles)
                .flatMap(Collection::stream)
                .filter(File::isDirectory)
                .collect(Collectors.toSet());
            System.out.println("files: " + files);
            watchFiles.addAll(files);
            excludes.addAll(watchTarget.getExcludes());
        }
        populateDefaultExcludeRules(excludes);
        log.info("Excludes files to watching: {}", excludes);

        // set exclude file filter with pattern matcher
        PatternMatcher patternsMatcher =
            PatternMatcherFactory.getPatternsMatcher(false, true, excludes);
        watcher.setExcludeFileFilter(new FileMatchingFilter(patternsMatcher));

        log.info("Watching files: {}", watchFiles);
        watcher.addSourceDirectories(watchFiles);
    }

    private static void populateDefaultExcludeRules(Set<String> excludes) {
        if (excludes == null) {
            return;
        }
        excludes.add("**/build/**");
        excludes.add("**/.gradle/**");
        excludes.add("**/gradle/**");
        excludes.add("**/.idea/**");
        excludes.add("**/.git/**");
        excludes.add("**/dist/**");
        excludes.add("**/node_modules/**");
        excludes.add("**/test/java/**");
        excludes.add("**/test/resources/**");
    }

    private File getPluginBuildFile() {
        Path buildLibPath = getProject().getBuildDir().toPath().resolve("libs");
        try (Stream<Path> pathStream = Files.find(buildLibPath, 1, (path, basicFileAttributes) -> {
            String fileName = path.getFileName().toString();
            return fileName.endsWith(".jar");
        })) {
            return pathStream.findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到插件jar包"))
                .toFile();
        } catch (IOException e) {
            throw new IllegalStateException("未找到插件jar包", e);
        }
    }

    private HttpClient createHttpClient() {
        String username = haloExtension.getSuperAdminUsername();
        String password = haloExtension.getSuperAdminPassword();
        return new CreateHttpClientStep(username, password).create();
    }

    private String getPluginName() {
        return pluginExtension.getPluginName();
    }
}
