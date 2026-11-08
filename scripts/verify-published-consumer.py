#!/usr/bin/env python3
"""Verify published Maven artifacts in a standalone consumer without an included build."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"


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


def main():
    BUILD.mkdir(exist_ok=True)
    version = next(line.split("=", 1)[1] for line in (ROOT / "gradle.properties").read_text().splitlines()
                   if line.startswith("version="))
    gradle(["publishAllPublicationsToVerificationRepository", "--init-script",
            str(ROOT / "gradle/verification-repository.init.gradle")], BUILD / "publication.log")

    repository = BUILD / "verification-repository"
    project = Path(tempfile.mkdtemp(prefix="publication-consumer-", dir=BUILD))
    repository_uri = groovy_literal(repository.as_uri())
    (project / "settings.gradle").write_text(
        "pluginManagement { repositories { maven { url = uri(" + repository_uri
        + ") }; gradlePluginPortal(); mavenCentral() } }\n"
        "rootProject.name = 'spring-paper-publication-consumer'\n")
    example = ROOT / "examples/guide-plugin"
    shutil.copytree(example / "src", project / "src")
    source = (example / "build.gradle").read_text().replace(
        "id 'org.blu3fishez.spring-paper'", "id 'org.blu3fishez.spring-paper' version " + groovy_literal(version), 1)
    source += "\nrepositories { maven { url = uri(" + repository_uri + ") } }\n"
    (project / "build.gradle").write_text(source)

    # 같은 SNAPSHOT의 Gradle 캐시가 남으면 방금 수정한 라이브러리 대신 이전 배포물을 검증할 수 있다.
    # 최초 소비는 refresh하고, 아래에서 모든 자체 클래스의 bytecode까지 현재 배포물과 비교한다.
    gradle(["-p", str(project), "build", "--refresh-dependencies"], BUILD / "published-consumer-first-build.log")
    second = gradle(["-p", str(project), "build"], BUILD / "published-consumer-second-build.log")
    if "Reusing configuration cache." not in second:
        raise AssertionError("Standalone consumer did not reuse its configuration cache")

    artifact = project / "build/libs" / ("spring-paper-publication-consumer-" + version + ".jar")
    with zipfile.ZipFile(artifact) as jar:
        names = jar.namelist()
        duplicates = len(names) - len(set(names))
        forbidden = {prefix: sum(name.startswith(prefix) for name in names)
                     for prefix in ("org/springframework/boot/", "org/bukkit/", "org/slf4j/")}
        if duplicates or any(forbidden.values()):
            raise AssertionError({"duplicate_entries": duplicates, "forbidden_packages": forbidden})
        providers = jar.read("META-INF/services/org.blu3fishez.springpaper.spi.SpringPaperModule").decode().splitlines()
        expected = {"org.blu3fishez.springpaper.jpa.JpaModule", "org.blu3fishez.springpaper.cache.CacheModule",
                    "org.blu3fishez.springpaper.security.SecurityModule"}
        if len(providers) != len(expected) or set(providers) != expected:
            raise AssertionError("Published integration module providers were lost or duplicated")
        checked_classes = 0
        for module in sorted(ROOT.glob("spring-paper-*")):
            if not module.is_dir() or module.name == "spring-paper-gradle-plugin":
                continue
            library = module / "build/libs" / (module.name + "-" + version + ".jar")
            with zipfile.ZipFile(library) as expected_jar:
                for name in expected_jar.namelist():
                    if name.startswith("org/blu3fishez/springpaper/") and name.endswith(".class"):
                        if jar.read(name) != expected_jar.read(name):
                            raise AssertionError("Consumer contains stale/different library bytecode: " + name)
                        checked_classes += 1
        descriptor = jar.read("plugin.yml").decode()
        if "main: org.blu3fishez.springpaper.example.ExamplePlugin\n" not in descriptor:
            raise AssertionError("Published plugin did not generate the example entrypoint")
        if "  springpaper:\n" not in descriptor or "    permission: springpaper.verify\n" not in descriptor:
            raise AssertionError("Published plugin did not generate command metadata")
        report = {"version": version, "repository": str(repository.relative_to(ROOT)),
                  "consumer": str(project.relative_to(ROOT)), "included_build": False,
                  "configuration_cache_reused": True, "jar": str(artifact.relative_to(ROOT)),
                  "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                  "current_library_classes_verified": checked_classes,
                  "duplicate_entries": duplicates, "forbidden_packages": forbidden,
                  "module_providers": providers}
    target = BUILD / "published-consumer-verification.json"
    target.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
