#!/usr/bin/env python3
"""Publish only into build/, then verify standalone legacy and ordinary host consumers."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
ROOT_METADATA = "META-INF/spring-paper/application.properties"
MODULE_SPI = "META-INF/services/org.blu3fishez.springpaper.spi.SpringPaperModule"
FORBIDDEN_PACKAGES = (
    "org/springframework/web/", "jakarta/servlet/", "javax/servlet/",
    "org/apache/catalina/", "org/apache/coyote/", "org/apache/tomcat/",
    "org/eclipse/jetty/", "org/mortbay/jetty/", "io/undertow/", "reactor/netty/",
    "org/bukkit/", "io/papermc/paper/", "com/destroystokyo/paper/", "com/velocitypowered/",
    "com/google/inject/", "org/slf4j/", "lombok/", "org/gradle/", "xyz/jpenilla/",
    "com/electronwill/nightconfig/", "org/objectweb/asm/", "com/github/jengelman/",
)
COMMON = ["config", "boot"]
CONSUMERS = (
    {"example": "guide-plugin", "host": "paper", "mode": "legacy",
     "root": "org.blu3fishez.springpaper.example.ExamplePlugin",
     "modules": COMMON + ["core", "data-jpa", "data-jdbc", "cache", "data-pdc", "security", "security-core"]},
    {"example": "boot-paper-plugin", "host": "paper", "mode": "application",
     "root": "examples.bootpaper.GameServerApplication", "modules": COMMON + ["core"]},
    {"example": "boot-velocity-plugin", "host": "velocity", "mode": "application",
     "root": "org.blu3fishez.springpaper.examples.bootvelocity.GatewayApplication",
     "modules": COMMON + ["velocity"]},
)


def groovy_literal(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"


def gradle(arguments, log):
    command = [str(ROOT / "gradlew"), *arguments, "--configuration-cache", "--console=plain"]
    print("Running:", " ".join(command), flush=True)
    with log.open("w") as output:
        result = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    content = log.read_text()
    if result.returncode:
        print("\n".join(content.splitlines()[-100:]), flush=True)
        raise SystemExit(result.returncode)
    print("Passed; log:", log.relative_to(ROOT), flush=True)
    return content


def properties(content):
    return dict(line.split("=", 1) for line in content.splitlines()
                if line.strip() and not line.startswith(("#", "!")))


def import_lines(content):
    return [line.strip() for line in content.splitlines() if line.strip() and not line.startswith("#")]


def standalone_project(parent, consumer, version, repository):
    project = parent / consumer["example"]
    project.mkdir()
    repository_uri = groovy_literal(repository.as_uri())
    (project / "settings.gradle").write_text(
        "pluginManagement { repositories { maven { url = uri(" + repository_uri
        + ") }; gradlePluginPortal(); mavenCentral() } }\nrootProject.name = '"
        + consumer["example"] + "-publication-consumer'\n")
    example = ROOT / "examples" / consumer["example"]
    shutil.copytree(example / "src", project / "src")
    plugin = "org.blu3fishez.spring-" + consumer["host"]
    source = (example / "build.gradle").read_text().replace(
        "id '" + plugin + "'", "id '" + plugin + "' version " + groovy_literal(version), 1)
    source += "\nrepositories { maven { url = uri(" + repository_uri + ") } }\n"
    source += "apply from: " + groovy_literal(ROOT / "gradle/verify-non-web.gradle") + "\n"
    source += """
abstract class PublishedRuntimeReport extends DefaultTask {
    @org.gradle.api.tasks.Input
    abstract org.gradle.api.provider.ListProperty<String> getCoordinates()
    @org.gradle.api.tasks.Classpath
    abstract org.gradle.api.file.ConfigurableFileCollection getBootCore()
    @org.gradle.api.tasks.OutputFile
    abstract org.gradle.api.file.RegularFileProperty getReportFile()
    @org.gradle.api.tasks.TaskAction void writeReport() {
        def destination = reportFile.get().asFile
        destination.parentFile.mkdirs()
        destination.text = groovy.json.JsonOutput.toJson([
            modules: coordinates.get(), bootCore: bootCore.files.collect { it.absolutePath }
        ]) + '\\n'
    }
}
tasks.register('writeVerificationRuntimeGraph', PublishedRuntimeReport) {
    dependsOn 'verifyNonWebRuntime'
    coordinates.set(tasks.named('verifyNonWebRuntime').flatMap { it.resolvedModules })
    bootCore.from(configurations.runtimeClasspath.filter { it.name == 'spring-boot-4.1.2.jar' })
    reportFile.set(layout.buildDirectory.file('verification-runtime-graph.json'))
}
"""
    (project / "build.gradle").write_text(source)
    return project


def verify_artifact(project, consumer, version, artifact=None):
    artifact = artifact or project / "build/libs" / (project.name + "-publication-consumer-" + version + ".jar")
    resolved = json.loads((project / "build/verification-runtime-graph.json").read_text())
    graph = resolved["modules"]
    required = {"org.springframework.boot:spring-boot:4.1.2",
                "org.springframework.boot:spring-boot-autoconfigure:4.1.2"}
    if not required.issubset(graph):
        raise AssertionError("Published application lost its Boot 4.1.2 transitive runtime: " + str(graph))
    if {item for item in graph if item.startswith("org.springframework.boot:")} != required:
        raise AssertionError("This product uses only Boot core/autoconfigure 4.1.2, without starters or feature modules")
    if len(resolved["bootCore"]) != 1:
        raise AssertionError("Expected exactly one resolved Boot 4.1.2 core artifact")
    boot_core = Path(resolved["bootCore"][0])
    expected_imports = set()
    with zipfile.ZipFile(artifact) as jar:
        names = jar.namelist()
        duplicates = len(names) - len(set(names))
        forbidden = {prefix: sum(name.startswith(prefix) for name in names) for prefix in FORBIDDEN_PACKAGES}
        if duplicates or any(forbidden.values()):
            raise AssertionError({"duplicate_entries": duplicates, "forbidden_packages": forbidden})
        if any(name.startswith("BOOT-INF/") for name in names):
            raise AssertionError("Native host plugins require a flat JAR, not a bootJar")
        if MODULE_SPI in names:
            raise AssertionError("Built-in modules must use Boot imports, not the compatibility SPI")
        metadata = properties(jar.read(ROOT_METADATA).decode())
        if metadata != {key: consumer[key] for key in ("host", "mode")} | {"root-class": consumer["root"]}:
            raise AssertionError("Wrong own-artifact root metadata: " + str(metadata))
        checked_classes = 0
        for short_name in consumer["modules"]:
            module = "spring-paper-" + short_name
            library = ROOT / module / "build/libs" / (module + "-" + version + ".jar")
            with zipfile.ZipFile(library) as expected:
                if IMPORTS in expected.namelist():
                    expected_imports.update(import_lines(expected.read(IMPORTS).decode()))
                for name in expected.namelist():
                    if name.startswith("org/blu3fishez/springpaper/") and name.endswith(".class"):
                        if jar.read(name) != expected.read(name):
                            raise AssertionError("Consumer contains stale/different library bytecode: " + name)
                        checked_classes += 1
        imports = import_lines(jar.read(IMPORTS).decode())
        if len(imports) != len(set(imports)) or not expected_imports.issubset(imports):
            raise AssertionError("Boot auto-configuration imports were lost or duplicated")
        if consumer["mode"] == "legacy" and expected_imports != {
                "org.blu3fishez.springpaper.jdbc.autoconfigure.JdbcAutoConfiguration",
                "org.blu3fishez.springpaper.jpa.autoconfigure.JpaAutoConfiguration",
                "org.blu3fishez.springpaper.cache.autoconfigure.CacheAutoConfiguration",
                "org.blu3fishez.springpaper.pdc.autoconfigure.PdcAutoConfiguration",
                "org.blu3fishez.springpaper.security.autoconfigure.SecurityAutoConfiguration"}:
            raise AssertionError("Expected all five built-in module Boot imports in the guide consumer")
        factories = properties(jar.read("META-INF/spring.factories").decode())
        listeners = set(factories["org.springframework.boot.SpringApplicationRunListener"].split(","))
        if not {"org.blu3fishez.springpaper.boot.NativeReadyRunListener",
                "org.springframework.boot.context.event.EventPublishingRunListener"}.issubset(listeners):
            raise AssertionError("spring.factories lost native handoff or Boot event publishing")
        if consumer["host"] == "paper":
            descriptor = jar.read("plugin.yml").decode()
            main = consumer["root"] if consumer["mode"] == "legacy" else "org.blu3fishez.springpaper.bootstrap.SpringPaperApplicationPlugin"
            if "main: " + main + "\n" not in descriptor or "velocity-plugin.json" in names:
                raise AssertionError("Wrong native Paper descriptor")
            if consumer["mode"] == "legacy" and ("  springpaper:\n" not in descriptor
                    or "    permission: springpaper.verify\n" not in descriptor):
                raise AssertionError("Published plugin lost legacy command metadata")
        else:
            descriptor = json.loads(jar.read("velocity-plugin.json"))
            if descriptor["main"] != "org.blu3fishez.springpaper.velocity.bootstrap.SpringVelocityApplicationPlugin" or "plugin.yml" in names:
                raise AssertionError("Wrong native Velocity descriptor")
        # These inactive adapters ship inside the explicitly allowed Boot core itself. Check provenance
        # byte-for-byte; they must never be mistaken for Servlet APIs, Spring Web or a web server.
        boot_support = sorted(name for name in names if name.startswith("org/springframework/boot/web/") and name.endswith(".class"))
        with zipfile.ZipFile(boot_core) as original:
            expected_support = sorted(name for name in original.namelist()
                                      if name.startswith("org/springframework/boot/web/") and name.endswith(".class"))
            if boot_support != expected_support or any(jar.read(name) != original.read(name) for name in boot_support):
                raise AssertionError("Boot core web adapter provenance differs from resolved 4.1.2 artifact")
    return {"example": consumer["example"], "host": consumer["host"], "mode": consumer["mode"],
            "consumer": str(project.relative_to(ROOT)), "included_build": False,
            "configuration_cache_reused": True, "runtime_graph_guard_passed": True,
            "flat_jar": True,
            "runtime_modules": graph, "jar": str(artifact.relative_to(ROOT)),
            "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
            "current_library_classes_verified": checked_classes, "duplicate_entries": duplicates,
            "forbidden_packages": forbidden, "root_metadata": metadata,
            "builtin_auto_configurations": sorted(expected_imports),
            "boot_core_provenance": {"jar": str(boot_core), "sha256": hashlib.sha256(boot_core.read_bytes()).hexdigest(),
                                     "web_support_classes_verified": len(boot_support)}}


def main():
    BUILD.mkdir(exist_ok=True)
    version = next(line.split("=", 1)[1] for line in (ROOT / "gradle.properties").read_text().splitlines()
                   if line.startswith("version="))
    gradle(["publishAllPublicationsToVerificationRepository", "--init-script",
            str(ROOT / "gradle/verification-repository.init.gradle")], BUILD / "publication.log")
    repository = BUILD / "verification-repository"
    parent = Path(tempfile.mkdtemp(prefix="publication-consumers-", dir=BUILD))
    reports = []
    for consumer in CONSUMERS:
        project = standalone_project(parent, consumer, version, repository)
        tasks = ["-p", str(project), "build", "writeVerificationRuntimeGraph"]
        # Refresh first same-version SNAPSHOT consumption; bytecode comparisons also reject stale libraries.
        gradle([*tasks, "--refresh-dependencies"], BUILD / (consumer["example"] + "-published-first.log"))
        second = gradle(tasks, BUILD / (consumer["example"] + "-published-second.log"))
        if "Reusing configuration cache." not in second:
            raise AssertionError("Standalone consumer did not reuse its configuration cache: " + project.name)
        reports.append(verify_artifact(project, consumer, version))
    report = {"version": version, "repository": str(repository.relative_to(ROOT)), "consumers": reports,
              "verifier_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "scope": "Build and inspect published consumers only; no host process or native file application"}
    target = BUILD / "published-consumer-verification.json"
    target.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"report": str(target.relative_to(ROOT)),
                      "consumers": [{key: item[key] for key in ("example", "jar", "current_library_classes_verified", "configuration_cache_reused")}
                                    for item in reports]}, indent=2), flush=True)


if __name__ == "__main__":
    main()
