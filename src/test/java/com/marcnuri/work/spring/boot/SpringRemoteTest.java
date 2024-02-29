package com.marcnuri.work.spring.boot;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.OS;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.apache.maven.shared.invoker.PrintStreamHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.apache.commons.io.FileUtils.copyDirectory;

class SpringRemoteTest {

  @TempDir
  Path tempDir;
  Path app;

  @BeforeEach
  void setup() throws IOException {
    copyDirectory(new File(".mvn"), tempDir.resolve(".mvn").toFile());
    Files.copy(new File("mvnw.cmd").toPath(), tempDir.resolve("mvnw.cmd"));
    Files.copy(new File("mvnw").toPath(), tempDir.resolve("mvnw"));
    final var resources = Files.createDirectories(tempDir.resolve("src").resolve("main").resolve("resources"));
    Files.writeString(resources.resolve("application.properties"), "spring.devtools.remote.secret=Falken");
    app = Files.createDirectories(tempDir.resolve("src").resolve("main").resolve("java").resolve("app"));
  }

  @Test
  void test() throws Exception {
    final var springBootVersion = "2.7.11";
    Files.writeString(tempDir.resolve("pom.xml"), pom(springBootVersion));
    Files.writeString(app.resolve("Application.java"), application("Greetings Professor Falken"));
    // Initial compilation
    final InvocationRequest ir = new DefaultInvocationRequest();
    ir.setBaseDirectory(tempDir.toFile());
    ir.setGoals(List.of("package"));
    final var result = maven(ir);
    // Copy packaged application so that it can be overridden in subsequent package
    Files.copy(tempDir.resolve("target").resolve("spring-boot-remote-" + springBootVersion + ".jar"),
      tempDir.resolve("remote-app.jar"));
    final var execFactory = DefaultExecutor.builder().setWorkingDirectory(tempDir.toFile());
    // Extract jar to easily get the complete ClassPath
    final var extractJarCmd = CommandLine.parse("jar xf remote-app.jar BOOT-INF/lib");
    execFactory.get().execute(extractJarCmd);
    // Run the application
    final var remoteAppHandler = new DefaultExecuteResultHandler();
    final var remoteAppWatchDog = ExecuteWatchdog.builder().setTimeout(Duration.ofSeconds(100)).get();
    final var remoteAppCmd = CommandLine.parse("java -jar remote-app.jar");
    final var executor = execFactory.get();
    executor.setWatchdog(remoteAppWatchDog);
    executor.execute(remoteAppCmd, remoteAppHandler);
    // Run Spring Remote Dev
    final var springRemoteDevHandler = new DefaultExecuteResultHandler();
    final var springRemoteDevWatchDog = ExecuteWatchdog.builder().setTimeout(Duration.ofSeconds(100)).get();
    final var springRemoteDevCmd = new CommandLine("java");
    springRemoteDevCmd.addArgument("-cp");
    springRemoteDevCmd.addArgument(
      STR."\{tempDir.resolve("BOOT-INF").resolve("lib")}\{File.separator}*\{File.pathSeparator}" +
        STR."\{tempDir.resolve("target").resolve("classes")}"
    );
    springRemoteDevCmd.addArgument("org.springframework.boot.devtools.RemoteSpringApplication");
    springRemoteDevCmd.addArgument("http://localhost:8080");
    springRemoteDevCmd.addArgument("--trace");
    final var springRemoteDevExecutor = execFactory.get();
    springRemoteDevExecutor.setWatchdog(springRemoteDevWatchDog);
    springRemoteDevExecutor.execute(springRemoteDevCmd, springRemoteDevHandler);


    Files.writeString(app.resolve("Application.java"), application("Greetings Professor Falken!!!"));
    final var result2 = maven(ir);

    System.out.println(result);
    springRemoteDevHandler.waitFor(Duration.ofSeconds(10));
    remoteAppHandler.waitFor(Duration.ofSeconds(10));
    springRemoteDevWatchDog.destroyProcess();
    remoteAppWatchDog.destroyProcess();
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
