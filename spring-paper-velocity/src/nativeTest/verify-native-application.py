"""Replay the controlled native-loader proof with an existing fixed host JAR and final consumer JAR."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


workspace = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--artifact", required=True, type=Path)
parser.add_argument("--native-jar", type=Path, default=workspace / "build/velocity-verification/native-host/velocity-4.2.1-SNAPSHOT-43.jar")
parser.add_argument("--output", type=Path, help="New empty evidence directory; omitted creates a fresh temporary directory")
args = parser.parse_args()
artifact = args.artifact.resolve(strict=True)
native = args.native_jar.resolve(strict=True)
with zipfile.ZipFile(native) as jar:
    manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
    if "4.2.1-SNAPSHOT (git-9a5951c7-b43)" not in manifest:
        raise SystemExit("Native fixture requires the existing Velocity 4.2.1-SNAPSHOT build 43 binary")

if args.output:
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
else:
    output = Path(tempfile.mkdtemp(prefix="spring-paper-velocity-boot-native-"))
classes = output / "classes"
work = output / "work"
classes.mkdir()
work.mkdir()
# Startup would fail if runtime accidentally searched the implicit native server cwd.
(work / "application.properties").write_text("spring.config.import=must-not-be-read-from-implicit-cwd.properties\n", encoding="utf-8")
source = Path(__file__).resolve().parent / "java/NativeVelocityApplicationVerification.java"
compile_command = ["javac", "--class-path", str(native), "-d", str(classes), str(source)]
subprocess.run(compile_command, check=True, cwd=work)
run_command = ["java", "--class-path", os.pathsep.join([str(native), str(classes)]),
               "NativeVelocityApplicationVerification", str(artifact), str(work / "consumers")]
result = subprocess.run(run_command, cwd=work, capture_output=True, encoding="utf-8", errors="replace")
(output / "stdout.txt").write_text(result.stdout, encoding="utf-8")
(output / "stderr.txt").write_text(result.stderr, encoding="utf-8")
report = {
    "scope": "Final JAR -> native JSON loader/classloaders -> Guice -> real Boot -> public lifecycle EventTasks; no proxy process",
    "limits": "Controlled binding module/direct EventTask invocation; no native plugin-manager event dispatch, forwarding or client connection",
    "exitCode": result.returncode,
    "artifact": {"path": str(artifact), "sha256": sha256(artifact)},
    "native": {"path": str(native), "sha256": sha256(native), "version": "4.2.1-SNAPSHOT build 43 git-9a5951c7"},
    "sources": {str(source.relative_to(workspace)): sha256(source), str(Path(__file__).resolve().relative_to(workspace)): sha256(Path(__file__).resolve())},
    "compileCommand": compile_command,
    "runCommand": run_command,
    "workingDirectory": str(work),
    "implicitCwdNegativeControl": "Required missing import in cwd application.properties; startup must still succeed",
}
(output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(result.stdout, end="")
print(result.stderr, end="")
print("Evidence:", output / "report.json")
raise SystemExit(result.returncode)
