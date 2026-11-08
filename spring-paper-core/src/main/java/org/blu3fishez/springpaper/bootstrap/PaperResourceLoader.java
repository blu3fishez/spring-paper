package org.blu3fishez.springpaper.bootstrap;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 실제 Paper 26.3 기동에서는 CommandExecutor를 loadClass로 읽을 수 있어도
 * getResource("org/bukkit/command/CommandExecutor.class")는 null이었다.
 * Spring ConfigurationClassParser가 구현 인터페이스의 메타데이터를 읽다 실패한 원인이다.
 *
 * <p>GenericApplicationContext의 ResourceLoader 확장 지점에만 서버 리소스 fallback을 제공한다.
 * 빈/프록시의 클래스 로더는 원래 Paper 로더로 유지해 호스트 타입 정체성과 CGLIB 정의 위치를 보존한다.
 * Paper API를 플러그인 JAR에 넣거나 전역 클래스 로더를 변경해서 해결하면 안 된다.
 *
 * @see org.springframework.context.support.GenericApplicationContext#setResourceLoader
 */
final class PaperResourceLoader extends PathMatchingResourcePatternResolver {
    PaperResourceLoader(ClassLoader pluginLoader, ClassLoader platformLoader) {
        super(new ResourceClassLoader(pluginLoader, platformLoader));
    }

    /** Delegates all class loading; only resource visibility differs from the plugin loader. */
    private static final class ResourceClassLoader extends ClassLoader {
        private final ClassLoader platformLoader;

        ResourceClassLoader(ClassLoader pluginLoader, ClassLoader platformLoader) {
            super(pluginLoader);
            this.platformLoader = platformLoader;
        }

        @Override public URL getResource(String name) {
            // 같은 경로는 플러그인 것을 우선한다. 서버 fallback이 소비자의 설정을 덮지 않게 한다.
            URL resource = getParent().getResource(name);
            return resource != null ? resource : platformLoader.getResource(name);
        }

        @Override public Enumeration<URL> getResources(String name) throws IOException {
            // classpath*: 검색은 양쪽을 보되 동일 URL을 중복 스캔해 빈/엔티티를 재등록하지 않는다.
            var resources = new LinkedHashSet<URL>();
            resources.addAll(Collections.list(getParent().getResources(name)));
            resources.addAll(Collections.list(platformLoader.getResources(name)));
            return Collections.enumeration(resources);
        }
    }
}
