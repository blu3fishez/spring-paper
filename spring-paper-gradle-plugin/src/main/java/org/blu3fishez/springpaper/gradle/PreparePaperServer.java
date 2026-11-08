package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/** Developer-only files. EULA acceptance must be explicitly configured by the user. */
@DisableCachingByDefault(because = "Merges an existing, mutable developer server configuration")
public abstract class PreparePaperServer extends DefaultTask {
    @Input public abstract Property<Boolean> getAcceptEula();
    @Input public abstract MapProperty<String, String> getServerProperties();
    @OutputFile public abstract RegularFileProperty getServerPropertiesFile();
    @OutputFile public abstract RegularFileProperty getEulaFile();

    @TaskAction public void prepare() throws IOException {
        var target = getServerPropertiesFile().get().getAsFile().toPath();
        Files.createDirectories(target.getParent());
        Properties properties = new Properties();
        if (Files.exists(target)) try (var input = Files.newInputStream(target)) { properties.load(input); }
        properties.putAll(getServerProperties().get());
        try (var writer = Files.newBufferedWriter(target)) { properties.store(writer, "Spring Paper development server"); }
        var eula = getEulaFile().get().getAsFile().toPath();
        Files.createDirectories(eula.getParent());
        // 서버 실행 허용과 Minecraft EULA 수락은 별도 의사표시다. 수락 대기 중 실제 기동을 보류했던 지점이다.
        // 배포 예제는 false를 유지하고, 사용자가 수락한 로컬 설정에서만 true를 전달한다. 자동 수락하지 않는다.
        Files.writeString(eula, "# https://aka.ms/MinecraftEULA\neula=" + getAcceptEula().get() + "\n");
    }
}
