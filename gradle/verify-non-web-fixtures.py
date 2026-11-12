#!/usr/bin/env python3
"""Exercise the runtime gate with resolved local Maven graphs, without downloading web libraries."""

import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.0-fixture"

# Independent examples of the agreed boundary, not values read from the implementation.
ALLOWED = [
    "org.springframework:spring-context",
    "org.springframework:spring-jdbc",
    "org.springframework.boot:spring-boot",
    "org.springframework.boot:spring-boot-autoconfigure",
    "org.springframework.boot:spring-boot-jdbc",
    "org.springframework.boot:spring-boot-transaction",
    "org.springframework.boot:spring-boot-starter",
    "org.springframework.boot:spring-boot-starter-jdbc",
    "org.springframework.boot:spring-boot-starter-actuator",
    "io.netty:netty-transport",
    "io.netty:netty-transport-native-epoll",
    "io.netty:netty-codec-http",
    "io.projectreactor:reactor-core",
    "org.apache.httpcomponents.client5:httpclient5",
    "com.squareup.okhttp3:okhttp",
    "com.example:spring-web",
    "com.example:tomcat-embed-core",
]

FORBIDDEN = [
    "org.springframework:spring-web",
    "org.springframework:spring-webmvc",
    "org.springframework:spring-webflux",
    "org.springframework.boot:spring-boot-starter-web",
    "org.springframework.boot:spring-boot-starter-webmvc",
    "org.springframework.boot:spring-boot-starter-webflux",
    "org.springframework.boot:spring-boot-starter-websocket",
    "org.springframework.boot:spring-boot-starter-web-services",
    "org.springframework.boot:spring-boot-starter-jersey",
    "org.springframework.boot:spring-boot-starter-tomcat",
    "org.springframework.boot:spring-boot-starter-jetty",
    "org.springframework.boot:spring-boot-starter-undertow",
    "org.springframework.boot:spring-boot-starter-reactor-netty",
    "org.springframework.boot:spring-boot-web-server",
    "org.springframework.boot:spring-boot-servlet",
    "org.springframework.boot:spring-boot-webmvc",
    "org.springframework.boot:spring-boot-webflux",
    "org.springframework.boot:spring-boot-http-converter",
    "org.springframework.boot:spring-boot-http-codec",
    "org.springframework.boot:spring-boot-http-client",
    "org.springframework.boot:spring-boot-restclient",
    "org.springframework.boot:spring-boot-reactor-netty",
    "org.apache.tomcat.embed:tomcat-embed-core",
    "org.apache.tomcat:tomcat-catalina",
    "org.apache.tomcat:tomcat-jdbc",
    "org.apache.tomcat:tomcat-juli",
    "org.eclipse.jetty:jetty-server",
    "org.eclipse.jetty.ee11:jetty-ee11-servlet",
    "org.mortbay.jetty:jetty",
    "io.undertow:undertow-core",
    "jakarta.servlet:jakarta.servlet-api",
    "javax.servlet:javax.servlet-api",
    "org.jboss.spec.javax.servlet:jboss-servlet-api_4.0_spec",
    "org.apache.geronimo.specs:geronimo-servlet_3.0_spec",
    "io.projectreactor.netty:reactor-netty-http",
    "io.projectreactor.ipc:reactor-netty",
]


def groovy_literal(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"


def publish(repository, coordinate, dependencies=()):
    """Publish synthetic POMs/JARs: the real resolver supplies ModuleComponentIdentifiers."""
    group, artifact = coordinate.split(":")
    target = repository / group.replace(".", "/") / artifact / VERSION
    target.mkdir(parents=True)
    pom = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
    for key, value in (("modelVersion", "4.0.0"), ("groupId", group),
                       ("artifactId", artifact), ("version", VERSION)):
        ET.SubElement(pom, key).text = value
    if dependencies:
        entries = ET.SubElement(pom, "dependencies")
        for dependency in dependencies:
            dependency_group, dependency_artifact = dependency.split(":")
            entry = ET.SubElement(entries, "dependency")
            for key, value in (("groupId", dependency_group), ("artifactId", dependency_artifact),
                               ("version", VERSION), ("scope", "runtime")):
                ET.SubElement(entry, key).text = value
    ET.ElementTree(pom).write(target / f"{artifact}-{VERSION}.pom", encoding="utf-8", xml_declaration=True)
    with zipfile.ZipFile(target / f"{artifact}-{VERSION}.jar", "w") as jar:
        jar.writestr("fixture.txt", "Synthetic local dependency; no third-party bytecode.\n")


def run_gradle(project, name, tasks, expected_failure=False, reuse=False):
    command = [str(ROOT / "gradlew"), "-p", str(project), *tasks, "--offline", "--configuration-cache",
               "--configuration-cache-problems=fail", "--console=plain", "--max-workers=2"]
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (project / f"{name}.log").write_text(result.stdout)
    if (result.returncode != 0) != expected_failure:
        print(result.stdout, flush=True)
        raise AssertionError(f"Unexpected exit code {result.returncode}: {' '.join(command)}")
    if reuse and "Reusing configuration cache." not in result.stdout:
        raise AssertionError(f"{name} did not reuse the configuration cache")
    if ":tooling:verifyNonWebRuntime" in result.stdout:
        raise AssertionError("Gradle plugin tooling was checked as application runtime")
    print(f"PASS {name}; log: {project / (name + '.log')}", flush=True)
    return result.stdout


def assert_forbidden(output, classpath):
    if f"{classpath} violates the non-web application runtime boundary:" not in output:
        raise AssertionError("Missing application/classpath diagnostic")
    for coordinate in FORBIDDEN:
        if f"  - {coordinate}:{VERSION} (" not in output:
            raise AssertionError(f"Transitive forbidden module was not identified: {coordinate}")


def main():
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix="non-web-fixtures-", dir=build))
    repository = project / "repository"
    for coordinate in ALLOWED + FORBIDDEN:
        publish(repository, coordinate)
    publish(repository, "fixture:web-facade", FORBIDDEN)
    publish(repository, "fixture:application-bridge", ["fixture:web-facade"])
    (project / "settings.gradle").write_text(
        "rootProject.name = 'non-web-boundary-fixtures'\ninclude 'app', 'tooling'\n")
    (project / "build.gradle").write_text(
        "plugins { id 'base' }\n"
        "subprojects { repositories { maven { url = uri(" + groovy_literal(repository.as_uri()) + ") } } }\n"
        "apply from: " + groovy_literal(ROOT / "gradle/verify-non-web.gradle") + "\n"
        "tasks.named('check') { dependsOn 'verifyNonWebRuntime' }\n")
    app = project / "app"
    tooling = project / "tooling"
    app.mkdir()
    tooling.mkdir()
    application = "plugins { id 'java-library' }\ndependencies {\n"
    application += "\n".join(f"    runtimeOnly '{coordinate}:{VERSION}'" for coordinate in ALLOWED)
    # Host compileOnly, test runtime, and tooling graphs must not become application runtime.
    application += f"\n    compileOnly 'jakarta.servlet:jakarta.servlet-api:{VERSION}'\n"
    application += f"    testRuntimeOnly 'org.springframework:spring-webflux:{VERSION}'\n}}\n"
    (app / "build.gradle").write_text(application)
    (tooling / "build.gradle").write_text(
        "plugins { id 'java-gradle-plugin' }\n"
        f"dependencies {{ implementation 'org.springframework.boot:spring-boot-starter-web:{VERSION}' }}\n")

    tasks = ["check", ":app:check", "verifyNonWebRuntime"]
    positive = run_gradle(project, "allowed-first", tasks)
    if ":app:verifyNonWebRuntime" not in positive:
        raise AssertionError("Application runtime check was not connected to check")
    run_gradle(project, "allowed-reused", tasks, reuse=True)

    (app / "build.gradle").write_text(
        application + f"dependencies {{ runtimeOnly 'fixture:application-bridge:{VERSION}' }}\n")
    for name, reuse in (("forbidden-first", False), ("forbidden-reused", True)):
        negative = run_gradle(project, name, tasks, expected_failure=True, reuse=reuse)
        assert_forbidden(negative, ":app:runtimeClasspath")

    (app / "build.gradle").write_text(
        application + f"dependencies {{ runtimeOnly 'fixture:missing:{VERSION}' }}\n")
    unresolved = run_gradle(project, "unresolved", ["verifyNonWebRuntime"], expected_failure=True)
    if "Cannot verify :app:runtimeClasspath" not in unresolved or f"fixture:missing:{VERSION}" not in unresolved:
        raise AssertionError("Unresolved runtime dependencies did not fail closed with a useful diagnostic")

    standalone = project / "standalone"
    standalone.mkdir()
    (standalone / "settings.gradle").write_text("rootProject.name = 'standalone-root-consumer'\n")
    standalone_application = application + (
        "repositories { maven { url = uri(" + groovy_literal(repository.as_uri()) + ") } }\n"
        "apply from: " + groovy_literal(ROOT / "gradle/verify-non-web.gradle") + "\n")
    (standalone / "build.gradle").write_text(standalone_application)
    root_positive = run_gradle(standalone, "root-allowed-first", ["check"])
    if "> Task :verifyNonWebRuntime" not in root_positive:
        raise AssertionError("Standalone root Java runtime check was not connected to check")
    run_gradle(standalone, "root-allowed-reused", ["check"], reuse=True)
    (standalone / "build.gradle").write_text(
        standalone_application + f"dependencies {{ runtimeOnly 'fixture:application-bridge:{VERSION}' }}\n")
    for name, reuse in (("root-forbidden-first", False), ("root-forbidden-reused", True)):
        root_negative = run_gradle(standalone, name, ["check"], expected_failure=True, reuse=reuse)
        assert_forbidden(root_negative, ":runtimeClasspath")

    report = {"local_maven_fixtures": True, "downloaded_web_libraries": False,
              "allowed_module_examples": len(ALLOWED), "forbidden_transitive_modules": len(FORBIDDEN),
              "compile_only_and_test_runtime_ignored": True, "gradle_plugin_tooling_ignored": True,
              "allowed_and_rejected_configuration_cache_reused": True, "unresolved_dependencies_rejected": True,
              "standalone_root_java_runtime_checked": True, "standalone_root_configuration_cache_reused": True}
    (project / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
