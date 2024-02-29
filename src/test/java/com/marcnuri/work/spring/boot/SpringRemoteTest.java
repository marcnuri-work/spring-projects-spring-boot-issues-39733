package com.marcnuri.work.spring.boot;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.OS;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.apache.maven.shared.invoker.PrintStreamHandler;
import org.awaitility.core.ConditionEvaluationListener;
import org.awaitility.core.EvaluatedCondition;
import org.awaitility.core.TimeoutEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.apache.commons.io.FileUtils.copyDirectory;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.AssertionFailureBuilder.assertionFailure;

class SpringRemoteTest {

  @TempDir
  Path tempDir;
  private Set<ExecuteWatchdog> procs;
  private Path app;

  @BeforeEach
  void setUp() throws IOException {
    procs = new HashSet<>();
    copyDirectory(new File(".mvn"), tempDir.resolve(".mvn").toFile());
    Files.copy(new File("mvnw.cmd").toPath(), tempDir.resolve("mvnw.cmd"));
    Files.copy(new File("mvnw").toPath(), tempDir.resolve("mvnw"));
    final var resources = Files.createDirectories(tempDir.resolve("src").resolve("main").resolve("resources"));
    Files.writeString(resources.resolve("application.properties"), "spring.devtools.remote.secret=Falken");
    app = Files.createDirectories(tempDir.resolve("src").resolve("main").resolve("java").resolve("app"));
  }

  @AfterEach
  void tearDown() {
    procs.forEach(ExecuteWatchdog::destroyProcess);
  }

  @DisplayName("Live Reload")
  @ParameterizedTest(name = "{index}: Live Reload with Spring Boot {0}")
  @ValueSource(strings = {"2.7.11", "2.7.12"})
  void liveReload(String springBootVersion) throws Exception {
    // Bootstrap Project
    Files.writeString(tempDir.resolve("pom.xml"), pom(springBootVersion));
    Files.writeString(app.resolve("Application.java"), application("Greetings Professor Falken"));

    // Initial compilation
    final var mvnPackage = new DefaultInvocationRequest()
      .setBaseDirectory(tempDir.toFile())
      .setGoals(List.of("package"));
    maven(mvnPackage);

    // Copy packaged application so that it can be overridden in subsequent package
    Files.copy(tempDir.resolve("target").resolve(STR."spring-boot-remote-\{springBootVersion}.jar"),
      tempDir.resolve("remote-app.jar"));

    // Extract jar to easily get the complete ClassPath
    run("jar", "xf", "remote-app.jar", "BOOT-INF/lib").handler().waitFor(Duration.ofSeconds(10));

    // Run the application
    final var remoteApp = run("java", "-jar", "remote-app.jar");
    await().atMost(Duration.ofSeconds(5))
      .until(() -> remoteApp.out.toString().contains("Started Application in "));

    // Run Spring Remote Dev
    final var springRemoteDev = run("java", "-cp",
      STR."\{tempDir.resolve("BOOT-INF").resolve("lib")}\{File.separator}*\{File.pathSeparator}" +
        STR."\{tempDir.resolve("target").resolve("classes")}",
      "org.springframework.boot.devtools.RemoteSpringApplication",
      "http://localhost:8080",
      "--trace"
    );
    await().atMost(Duration.ofSeconds(5))
      .until(() -> springRemoteDev.out.toString().contains("LiveReload server is running on port "));

    // Modify the application and recompile
    Files.writeString(app.resolve("Application.java"), application("Greetings Professor Falken!!!"));
    maven(mvnPackage);

    // Verify restart
    await().atMost(Duration.ofSeconds(5))
      .conditionEvaluationListener(onTimeout(noOp -> assertionFailure()
        .message(STR."Expected application to restart but got:\{remoteApp.out.toString()}").buildAndThrow()))
      .until(() -> remoteApp.out.toString()
        .contains("[  restartedMain] app.Application                          : Started Application in "));
    await().atMost(Duration.ofSeconds(5))
      .until(() -> springRemoteDev.out.toString().contains("Uploading 2 class path changes"));
    await().atMost(Duration.ofSeconds(5))
      .until(() -> springRemoteDev.out.toString().contains("Remote server has changed, triggering LiveReload"));
  }

  private <T> ConditionEvaluationListener<T> onTimeout(Consumer<TimeoutEvent> onTimeout) {
    return new ConditionEvaluationListener<>() {
      @Override
      public void conditionEvaluated(EvaluatedCondition condition) {
        // NO-OP
      }

      @Override
      public void onTimeout(TimeoutEvent timeoutEvent) {
        onTimeout.accept(timeoutEvent);
      }
    };
  }

  private static String maven(InvocationRequest ir) {
    final Invoker invoker = new DefaultInvoker();
    if (OS.isFamilyWindows()) {
      invoker.setMavenExecutable(new File("mvnw.cmd"));
    } else {
      invoker.setMavenExecutable(new File("mvnw"));
    }
    try (var baos = new ByteArrayOutputStream(); var ps = new PrintStream(baos)) {
      ir.setBatchMode(true);
      ir.setOutputHandler(new PrintStreamHandler(ps, true));
      final var result = invoker.execute(ir);
      if (result.getExitCode() != 0) {
        throw new RuntimeException(baos.toString());
      }
      return baos.toString();
    } catch (IOException | MavenInvocationException e) {
      throw new RuntimeException(e);
    }
  }

  private Run run(String... args) throws IOException {
    final var watchDog = ExecuteWatchdog.builder().setTimeout(Duration.ofHours(1)).get();
    procs.add(watchDog);
    final var commandLine = new CommandLine(args[0]);
    for (int i = 1; i < args.length; i++) {
      commandLine.addArgument(args[i]);
    }
    final var executor = DefaultExecutor.builder().setWorkingDirectory(tempDir.toFile()).get();
    executor.setWatchdog(watchDog);
    final var out = new ByteArrayOutputStream();
    executor.setStreamHandler(new PumpStreamHandler(out));
    final var handler = new DefaultExecuteResultHandler();
    executor.execute(commandLine, handler);
    return new Run(handler, out);
  }

  record Run(DefaultExecuteResultHandler handler, ByteArrayOutputStream out) {
  }

  private static String pom(String springBootVersion) {
    return STR
      ."""
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://maven.apache.org/POM/4.0.0"
               xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
        <modelVersion>4.0.0</modelVersion>
        <parent>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-starter-parent</artifactId>
          <version>\{springBootVersion}</version>
        </parent>
        <groupId>com.marcnuri.work</groupId>
        <artifactId>spring-boot-remote</artifactId>
        <dependencies>
          <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
          </dependency>
          <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-devtools</artifactId>
            <scope>runtime</scope>
          </dependency>
        </dependencies>
        <build>
          <plugins>
            <plugin>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-maven-plugin</artifactId>
              <configuration>
                <excludeDevtools>false</excludeDevtools>
              </configuration>
            </plugin>
          </plugins>
        </build>
      </project>
      """;
  }

  private static String application(String greeting) {
    return STR
      ."""
      package app;

      import org.springframework.boot.SpringApplication;
      import org.springframework.boot.autoconfigure.SpringBootApplication;
      import org.springframework.web.bind.annotation.GetMapping;
      import org.springframework.web.bind.annotation.RestController;

      @SpringBootApplication
      public class Application
      {
        public static void main(String[] args)
        {
          SpringApplication.run(Application.class, args);
        }
        @RestController
        class GreetingController
        {
          @GetMapping("/")
          String greet()
          {
            return "\{greeting}";
          }
        }
      }
      """;
  }

}
