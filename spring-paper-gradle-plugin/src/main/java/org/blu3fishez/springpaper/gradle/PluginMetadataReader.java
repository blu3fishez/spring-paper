package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reads metadata without loading a JavaPlugin or any server class into Gradle.
 * Paper 타입을 reflection으로 로딩하면 compileOnly 호스트 클래스와 런타임 의존성까지 Gradle에 요구하게 된다.
 * 어노테이션의 문자열 메타데이터만 ASM으로 읽어 빌드와 서버의 클래스 로더 책임을 분리한다.
 */
final class PluginMetadataReader {
    private static final String PREFIX = "Lorg/blu3fishez/springpaper/annotation/";
    final Map<String, Map<String, Object>> applications = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> commands = new LinkedHashMap<>();

    void read(Path path, boolean applicationSource) throws IOException {
        if (Files.isDirectory(path)) {
            try (var files = Files.walk(path)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    inspect(Files.readAllBytes(file), applicationSource);
                }
            }
        } else if (path.toString().endsWith(".jar")) {
            try (ZipFile jar = new ZipFile(path.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/versions/")) continue;
                    try (var input = jar.getInputStream(entry)) { inspect(input.readAllBytes(), applicationSource); }
                }
            }
        }
    }
    private void inspect(byte[] bytes, boolean applicationSource) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            private String name;
            @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
                this.name = name.replace('/', '.');
            }
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                Map<String, Object> data;
                if (applicationSource && descriptor.equals(PREFIX + "SpringPaperPluginApplication;")) {
                    data = applications.computeIfAbsent(name, key -> new LinkedHashMap<>());
                } else if (descriptor.equals(PREFIX + "PaperCommandExecutor;")) {
                    data = commands.computeIfAbsent(name, key -> new LinkedHashMap<>());
                } else return null;
                return visitor(data);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    private static AnnotationVisitor visitor(Map<String, Object> target) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object value) { target.put(name, value.toString()); }
            @Override public void visitEnum(String name, String descriptor, String value) { target.put(name, value); }
            @Override public AnnotationVisitor visitArray(String name) {
                List<String> values = new ArrayList<>();
                target.put(name, values);
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public void visit(String ignored, Object value) { values.add(value.toString()); }
                };
            }
        };
    }
}
