package org.blu3fishez.springpaper.bootstrap;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Arrays;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.blu3fishez.springpaper.annotation.PaperStart;
import org.blu3fishez.springpaper.annotation.PaperStop;
import org.blu3fishez.springpaper.command.PaperCommandRegistrar;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.config.PaperEnvironment;
import org.blu3fishez.springpaper.config.PaperServerInfo;
import org.blu3fishez.springpaper.lifecycle.PaperLifecycleManager;
import org.blu3fishez.springpaper.listener.PaperListenerRegistrar;
import org.blu3fishez.springpaper.service.PaperInvocationService;
import org.blu3fishez.springpaper.spi.PaperInvocationInterceptor;
import org.blu3fishez.springpaper.spi.SpringPaperModule;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;

/** One plugin-owned application, with no JVM-global context or server shutdown policy. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class SpringPaperApplication implements AutoCloseable {
    private final JavaPlugin plugin;
    @Getter
    @Accessors(fluent = true, chain = false)
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private PaperLifecycleManager lifecycle;
    private PaperListenerRegistrar listeners;
    private PaperCommandRegistrar commands;
    private boolean closed;

    public static SpringPaperApplication run(JavaPlugin plugin) {
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Start Spring Paper on the server thread");
        SpringPaperApplication application = new SpringPaperApplication(plugin);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(plugin.getClass().getClassLoader());
        try { application.start(); return application; }
        catch (Throwable error) {
            try { application.close(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("Spring Paper startup failed for " + plugin.getName(), error);
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }

    private void start() throws Exception {
        SpringPaperPluginApplication annotation = plugin.getClass().getAnnotation(SpringPaperPluginApplication.class);
        if (annotation == null) throw new IllegalArgumentException("Plugin requires @SpringPaperPluginApplication");
        ClassLoader loader = plugin.getClass().getClassLoader();
        // 클래스 정의와 리소스 탐색은 다른 책임이다. PaperResourceLoader의 실제 기동 실패 기록 참고.
        context.setClassLoader(loader);
        context.setResourceLoader(new PaperResourceLoader(loader, JavaPlugin.class.getClassLoader()));
        context.setAllowBeanDefinitionOverriding(false);
        context.setEnvironment(PaperEnvironment.load(plugin, annotation.configuration()));
        PaperConfiguration configuration = new PaperConfiguration(context.getEnvironment());
        var factory = context.getBeanFactory();
        factory.registerSingleton("paperPlugin", plugin);
        factory.registerSingleton("paperServer", plugin.getServer());
        factory.registerSingleton("paperLogger", plugin.getLogger());
        factory.registerSingleton("paperConfiguration", configuration);
        factory.registerSingleton("paperServerInfo", new PaperServerInfo(plugin.getServer().getMinecraftVersion(),
                plugin.getServer().getBukkitVersion(), plugin.getServer().getVersion(), Runtime.version().toString()));
        factory.registerSingleton("paperThreadAccess", (PaperThreadAccess) () -> plugin.getServer().isPrimaryThread());
        // 기존 Bukkit 설정의 검증/host 객체 캡처는 Paper thread에서 끝내고 refresh worker에 값으로 전달한다.
        // Spring의 표준 initializer를 사용하며 initializer 자체를 별도 컨테이너/서비스 로케이터로 만들지 않는다.
        for (var initializer : annotation.initializers()) {
            org.springframework.beans.BeanUtils.instantiateClass(initializer).initialize(context);
        }
        context.addBeanFactoryPostProcessor(beans -> {
            for (String reserved : java.util.List.of("paperPlugin", "paperServer", "paperLogger", "paperConfiguration", "paperServerInfo", "paperThreadAccess")) {
                if (beans.containsBeanDefinition(reserved)) throw new IllegalArgumentException("Reserved Spring Paper bean name: " + reserved);
            }
            for (String name : beans.getBeanDefinitionNames()) {
                Class<?> type = beans.getType(name, false);
                if (type == null) continue;
                boolean adapter = beans.findAnnotationOnBean(name, PaperEventListener.class, false) != null
                        || !AnnotatedElementUtils.getMergedRepeatableAnnotations(type, PaperCommandExecutor.class).isEmpty()
                        || Arrays.stream(org.springframework.util.ReflectionUtils.getAllDeclaredMethods(type))
                        .anyMatch(method -> method.isAnnotationPresent(PaperStart.class) || method.isAnnotationPresent(PaperStop.class));
                var definition = beans.getBeanDefinition(name);
                if (adapter && (definition.isLazyInit() || !definition.isSingleton())) {
                    throw new IllegalArgumentException(name + ": Paper adapters and lifecycle hooks must be eager singletons; exclude inactive modes in configuration");
                }
            }
        });
        context.register(PaperCoreConfiguration.class);
        context.register(annotation.configurations());
        Arrays.stream(annotation.scanBasePackageClasses()).map(Class::getPackageName).distinct().forEach(context::scan);
        ServiceLoader.load(SpringPaperModule.class, loader).forEach(module -> {
            if (configuration.enabled(module.enabledProperty())) context.register(module.configurationClass());
        });
        // 컨텍스트 refresh는 DB 연결/스키마 검사를 포함한다. 작업자에서 준비하되 완료를 기다린다.
        // 기다리지 않고 onEnable을 반환하면 미완성 컨텍스트로 이벤트가 들어오는 시작 경쟁이 생긴다.
        // Spring의 PostConstruct/SmartLifecycle은 이 작업자에서 호출되므로 Paper 변경은 아래 훅에서 한다.
        try (var preparation = Executors.newVirtualThreadPerTaskExecutor()) {
            preparation.submit(() -> withLoader(loader, context::refresh)).get();
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw error; }
        catch (ExecutionException error) { throw new IllegalStateException("Spring context preparation failed", error.getCause()); }
        PaperBeanCatalog catalog = context.getBean(PaperBeanCatalog.class);
        var candidates = catalog.candidates();
        lifecycle = new PaperLifecycleManager(context, candidates);
        lifecycle.start();
        var invocations = new PaperInvocationService(context.getBeanProvider(PaperInvocationInterceptor.class).orderedStream().toList(), loader);
        listeners = new PaperListenerRegistrar(plugin, invocations);
        commands = new PaperCommandRegistrar(plugin, invocations, context.getEnvironment()::resolveRequiredPlaceholders);
        // 같은 EventPriority의 전투 listener는 등록 순서도 게임 규칙이다. Spring 7의 getOrder가
        // Ordered, 타입 및 @Bean factory method metadata를 해석하므로 별도 순서 해석기를 만들지 않는다.
        var adapters = candidates.entrySet().stream().map(entry -> new Adapter(entry.getKey(), context.getBean(entry.getKey()), entry.getValue()))
                .sorted((left, right) -> {
                    boolean leftPriority = left.bean instanceof org.springframework.core.PriorityOrdered;
                    boolean rightPriority = right.bean instanceof org.springframework.core.PriorityOrdered;
                    if (leftPriority != rightPriority) return leftPriority ? -1 : 1;
                    int order = Integer.compare(context.getDefaultListableBeanFactory().getOrder(left.name, left.bean),
                            context.getDefaultListableBeanFactory().getOrder(right.name, right.bean));
                    return order == 0 ? left.name.compareTo(right.name) : order;
                }).toList();
        // Event relationships require the complete active batch, not one global number on every listener.
        listeners.registerAll(adapters.stream()
                .filter(entry -> factory.findAnnotationOnBean(entry.name, PaperEventListener.class, false) != null)
                .map(entry -> new PaperListenerRegistrar.Candidate(entry.name, entry.bean, entry.type)).toList());
        adapters.forEach(entry -> {
            for (PaperCommandExecutor command : AnnotatedElementUtils.getMergedRepeatableAnnotations(entry.type, PaperCommandExecutor.class)) {
                commands.register(entry.name, entry.bean, command);
            }
        });
        context.publishEvent(new PaperApplicationReadyEvent(plugin));
    }

    private static void withLoader(ClassLoader loader, Runnable action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try { action.run(); } finally { thread.setContextClassLoader(previous); }
    }
    private record Adapter(String name, Object bean, Class<?> type) {}

    @Override public void close() {
        if (closed) return;
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Stop Spring Paper on the server thread");
        closed = true;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(plugin.getClass().getClassLoader());
        RuntimeException failure = null;
        try {
            // 회귀 검증에서 Spring의 기본 destruction 순서만으로는 진행 중인 작업보다 DB가 먼저 닫힐 수 있었다.
            // 입력 차단 → 사용자 flush → 워커 drain → Spring 인프라 destruction 순서를 직접 보장한다.
            // 부분 시작 실패의 정리에서는 getBean으로 새 자원을 만들지 않고 이미 생성된 singleton만 조회한다.
            AutoCloseable workers = context.getBeanFactory().getSingleton(PaperCoreConfiguration.WORKERS_BEAN_NAME)
                    instanceof PaperTasks tasks ? tasks : null;
            for (AutoCloseable resource : new AutoCloseable[] { commands, listeners, lifecycle, workers, context }) {
                if (resource == null) continue;
                try { resource.close(); }
                catch (Exception error) {
                    if (failure == null) failure = new IllegalStateException("Spring Paper shutdown failed", error);
                    else failure.addSuppressed(error);
                }
            }
        } finally { Thread.currentThread().setContextClassLoader(previous); }
        if (failure != null) throw failure;
    }
}
