package org.blu3fishez.springpaper.gradle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges additional permission nodes without silently changing a command's declared grant policy. */
final class PluginPermissions {
    private PluginPermissions() {}
    static void merge(Object declared, Map<String, Object> target) {
        if (!(declared instanceof List<?> definitions)) throw new IllegalArgumentException("Application permissions must be annotations");
        for (Object definition : definitions) {
            if (!(definition instanceof Map<?, ?> values)) throw new IllegalArgumentException("Invalid permission metadata");
            Object identifier = values.get("name");
            if (!(identifier instanceof String name) || name.isBlank() || name.contains(" ")) {
                throw new IllegalArgumentException("Permission name must not be blank or contain spaces");
            }
            Object policy = values.containsKey("defaultValue") ? values.get("defaultValue") : "OP";
            String defaultValue = policy.toString().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            if (!List.of("op", "not op", "true", "false").contains(defaultValue)) {
                throw new IllegalArgumentException("Invalid permission default: " + defaultValue);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            Object previous = target.get(name);
            if (previous instanceof Map<?, ?> existing) {
                // 실제 소비자의 하위 명령 권한은 command 어노테이션만으로 발견되지 않았다.
                // 별도 권한을 보존하되 명령의 기본 허용 정책과 충돌하면 빌드를 실패시킨다.
                existing.forEach((key, value) -> result.put(key.toString(), value));
                if (!defaultValue.equals(result.get("default"))) throw new IllegalArgumentException("Conflicting permission: " + name);
            }
            result.put("default", defaultValue);
            Object description = values.get("description");
            if (description instanceof String text && !text.isBlank()) result.put("description", text);
            target.put(name, result);
        }
    }
}
