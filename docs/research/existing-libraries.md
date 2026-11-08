# Spring Paper 기존 라이브러리 조사

조사일: 2026-11-08 (Asia/Seoul). 공식 GitHub 저장소·기본 브랜치 소스·릴리스·빌드 파일을 확인했다. 라이브러리를 설치하거나 실행하지 않았고 SimpleExtraction 파일을 변경하지 않았다. 아래 버전은 **소스에 선언된 빌드 기준**이며, 최신 서버에서의 지원 보장이나 실행 검증 결과가 아니다.

## 판단

**현재 확인한 후보 중 Java 25 / Paper 26.3에서 곧바로 채택할 만큼 검증되고, Boot 없이 동작하며, 플러그인 단위 컨텍스트와 등록·해제 책임을 충족하는 라이브러리는 찾지 못했다.** 다만 기존 구현에는 상당한 참고 가치가 있다. 가장 가까운 경량 후보는 `lennertsoffers/spring-spigot`, 최근까지 개발된 실제 Spring 기반 후보는 Vital과 mainframe이다. 선택적 JPA·cache·security 모듈의 부재를 채택 제외 근거로 삼지는 않았다.

## 후보와 유지보수 근거

`pushed_at`은 태그·브랜치 작업에도 바뀔 수 있으므로 아래 날짜는 기본 브랜치의 마지막 커밋이다. GitHub Releases가 없는 것은 Maven 등의 배포가 없다는 뜻은 아니다.

| 후보 | 선언된 런타임·빌드 구성 | 마지막 기본 브랜치 커밋 / 배포 신호 | 라이선스 확인 |
|---|---|---|---|
| [lennertsoffers/spring-spigot](https://github.com/lennertsoffers/spring-spigot) | Spring Context 6.0.10, Spigot **서버 artifact** 1.20.1 compileOnly. Boot 없음. Java toolchain은 빌드에 명시되지 않음. | [2023-06-21, v1.0.1 작업](https://github.com/lennertsoffers/spring-spigot/commit/c904639d85932f24fdf7cf22f7ab449db076b0a8). 빌드 버전 1.0.1, GitHub Releases 없음. | [Apache-2.0](https://github.com/lennertsoffers/spring-spigot/blob/main/LICENSE.md) |
| [CChuYong/spigot-spring](https://github.com/CChuYong/spigot-spring) | Java 17, Paper API 1.20.1, Boot 3.1.1. AOP·JPA·WebFlux starter를 구현 모듈이 함께 의존. | [2023-09-03](https://github.com/CChuYong/spigot-spring/commit/68f83f68cb313cdf9c60b52e167dcc6962303c4a), [0.0.2 릴리스](https://github.com/CChuYong/spigot-spring/releases/tag/0.0.2). | [MIT](https://github.com/CChuYong/spigot-spring/blob/main/LICENSE) |
| [Spliterash/SpringSpigot](https://github.com/Spliterash/SpringSpigot) | Java 8 bytecode target, Spigot API 1.18.1, Boot 2.7.5. 별도 SpringSpigot 제공 플러그인 방식. | [2023-03-24](https://github.com/Spliterash/SpringSpigot/commit/18117aecfa1b6e48a3e6f0e70f3bfad1e833a3ef). 빌드 1.0.11, README 의존성 예시는 1.0.7, GitHub Releases 없음. | 명시적 LICENSE 파일과 GitHub 라이선스 식별 정보 미확인. |
| [Alan-Gomes/mcspring-boot](https://github.com/Alan-Gomes/mcspring-boot) | Java 8, Spigot API 1.13.2, Boot BOM 2.1.4.RELEASE. | [2020-07-06](https://github.com/Alan-Gomes/mcspring-boot/commit/6e657cadb35e08d5b3a366ac9cbfbde5826ea4bd). 소스 POM 0.20.7. 저장소는 현재 archived. GitHub 최신 릴리스는 [v0.14.0, 2019-05-25](https://github.com/Alan-Gomes/mcspring-boot/releases/tag/v0.14.0)로 Maven/소스 버전과 다름. | [MIT](https://github.com/Alan-Gomes/mcspring-boot/blob/master/LICENSE) |
| [ii1818nn/springlify](https://github.com/ii1818nn/springlify) | Java 17, Boot 3.2.4, 기존 `com.destroystokyo.paper` API 1.16.5. Bukkit·Velocity 플랫폼 모듈. | [2024-08-03](https://github.com/ii1818nn/springlify/commit/f61fb37be208f701b8430cd90cd9879831d50df6). [2024-07-31 릴리스 0.7.2.0-SNAPSHOT](https://github.com/ii1818nn/springlify/releases/tag/0.7.2.0-SNAPSHOT), 소스 0.7.2.1-SNAPSHOT. 저장소는 **2026-02-17 archived**. README 자체가 불안정한 개발 버전임을 고지. | [Apache-2.0](https://github.com/ii1818nn/springlify/blob/main/LICENSE) |
| [ThomasOM/spring-boot-starter-bukkit](https://github.com/ThomasOM/spring-boot-starter-bukkit) | Java 17, Spigot API 1.20.6, Boot starter 3.3.1, ACF Paper 명령 프레임워크. 실제 컨텍스트 시작은 Boot `SpringApplication`이 아니라 `AnnotationConfigApplicationContext`. | [2024-07-22](https://github.com/ThomasOM/spring-boot-starter-bukkit/commit/9acc2f79631bf84e1b4493c4220e43f52f0d639e), 소스 1.0-SNAPSHOT, GitHub Releases 없음. **2026-04-27 archived**. | 명시적 라이선스 미확인. |
| [mike-neumann/vital](https://github.com/mike-neumann/vital) | Java toolchain 24, Paper API 1.21.7, Boot 3.5.4. Kotlin 구현, Paper·Spigot·Bungee 지원 구조. | [2026-09-13](https://github.com/mike-neumann/vital/commit/225cf8ccc9aaa77081294e7d9fdee8de99fbbbb7), GitHub Releases 없음. Gradle은 태그가 없으면 dev-SNAPSHOT을 사용하며 안정 버전 후 Maven Central 배포를 TODO로 둠. | [Apache-2.0](https://github.com/mike-neumann/vital/blob/main/LICENSE) |
| [vjh0107/mainframe](https://github.com/vjh0107/mainframe) (기존 summit-systems/spring-bukkit URL이 이곳으로 연결됨) | Java release 21, Paper API 1.21.4, Boot BOM 4.1.0. core·Paper·Velocity·Gradle 모듈. | [2026-09-05](https://github.com/vjh0107/mainframe/commit/44de4cefa3f2ce022ca7052c46e4095a60549983), 소스 0.0.1-SNAPSHOT, GitHub Releases 없음. | 현재 트리에서 명시적 라이선스 미확인. 이전 spring-bukkit artifact의 라이선스를 현재 재작성된 코드에 그대로 적용할 근거는 확인하지 못함. |

빌드 기준의 직접 근거: [spring-spigot build](https://github.com/lennertsoffers/spring-spigot/blob/main/build.gradle.kts), [Spigot Spring bukkit-api build](https://github.com/CChuYong/spigot-spring/blob/main/bukkit-api/build.gradle.kts), [SpringSpigot build](https://github.com/Spliterash/SpringSpigot/blob/master/build.gradle.kts), [mcspring POM](https://github.com/Alan-Gomes/mcspring-boot/blob/master/pom.xml), [Springlify catalog](https://github.com/ii1818nn/springlify/blob/main/gradle/libs.versions.toml), [Springlify build](https://github.com/ii1818nn/springlify/blob/main/build.gradle), [ThomasOM POM](https://github.com/ThomasOM/spring-boot-starter-bukkit/blob/master/pom.xml), [Vital catalog](https://github.com/mike-neumann/vital/blob/main/gradle/libs.versions.toml), [Vital build](https://github.com/mike-neumann/vital/blob/main/build.gradle.kts), [mainframe BOM](https://github.com/vjh0107/mainframe/blob/main/bom/build.gradle.kts), [mainframe settings](https://github.com/vjh0107/mainframe/blob/main/settings.gradle.kts), [mainframe build](https://github.com/vjh0107/mainframe/blob/main/build.gradle.kts).

## 가장 가까운 Boot 없는 후보: spring-spigot

확인된 장점:

- `@SpigotPlugin` annotation processor가 `plugin.yml`과 설정 클래스를 생성한다. `@EventListener`, `@PluginCommandExecutor`는 Spring component로 취급되며 Bukkit listener와 기존 `plugin.yml` 명령 executor에 연결된다. 플러그인 인스턴스도 bean으로 등록한다. [사용법](https://github.com/lennertsoffers/spring-spigot/blob/main/README.md)
- `initialize(plugin)`마다 새 `AnnotationConfigApplicationContext`를 만든다. `SpringSpigot`의 static 필드는 컨텍스트 자체가 아닌 `ContextInitializer`이므로, **static이라는 이유만으로 전역 컨텍스트라고 결론내리면 안 된다.** 플러그인 코드가 반환값을 보관해 소유할 수 있다. [ContextInitializer](https://github.com/lennertsoffers/spring-spigot/blob/main/src/main/java/be/lennertsoffers/spigotbootstrapper/core/ContextInitializer.java)

현행 Paper 채택을 막는 구체적 차이:

1. `initialize()`가 인챈트먼트 bean 존재 여부와 무관하게 `registerEnchantments()`를 호출한다. 그 안에서 먼저 `Enchantment.class.getDeclaredField("acceptingNew")`에 접근한다. 정리도 `byKey`·`byName`에 의존한다. [EnchantmentLoader](https://github.com/lennertsoffers/spring-spigot/blob/main/src/main/java/be/lennertsoffers/spigotbootstrapper/core/EnchantmentLoader.java)와 [현재 Paper Enchantment 소스](https://github.com/PaperMC/Paper/blob/main/paper-api/src/main/java/org/bukkit/enchantments/Enchantment.java)를 대조하면 이 내부 필드와 과거 등록 API가 없다. **현재 API에서 초기화 실패가 예상된다는 소스 분석**이며, Paper 26.3 런타임을 실행한 결과는 아니다.
2. README의 `onDisable()` 예제는 `SpringSpigot.unload()`만 부른다. 실제 `unload()` → `cleanup()`은 인챈트먼트만 정리하고 반환했던 컨텍스트에 `close()`하지 않는다. 그대로 사용하면 Spring bean의 종료 처리를 보장하지 않는다. [SpringSpigot entrypoint](https://github.com/lennertsoffers/spring-spigot/blob/main/src/main/java/be/lennertsoffers/spigotbootstrapper/library/SpringSpigot.java)
3. `ContextInitializer`에는 listener 등록과 `command.setExecutor()`는 있지만 이에 대응하는 해제·복구가 없다. Bukkit의 **플러그인 disable 시 정리**와 라이브러리의 **컨텍스트 close·시작 실패 rollback 시 정리**는 다른 계약이다. 별도 수정이 필요하다. YAML 설정 로더나 `spring.paper` namespace도 확인하지 못했다. [등록·cleanup 구현](https://github.com/lennertsoffers/spring-spigot/blob/main/src/main/java/be/lennertsoffers/spigotbootstrapper/core/ContextInitializer.java)

평가: 의도한 사용성의 선례로 적합하다. 그러나 현재 버전을 의존성만 추가해 마이그레이션하는 선택은 권하지 않는다. 포크라면 위 초기화·종료 문제와 Spring 7/Paper 26.3 호환성 검증을 직접 책임져야 한다.

## 다른 실제 Spring 후보의 확인된 범위

### Spigot Spring (CChuYong)

`@EnableSpringSpigotSupport`를 붙인 빈 `JavaPlugin` 예제가 있고 annotated command controller, AOP/JPA/scheduling, YAML 로딩을 제공한다. [README](https://github.com/CChuYong/spigot-spring/blob/main/README.md) 그러나 구현은 제공 플러그인의 부모 컨텍스트와 소비 플러그인의 자식 컨텍스트를 구성하며, 기존 플러그인을 unload한 뒤 자체 classloader로 다시 다룬다. 종료 때 부모·자식 컨텍스트를 닫는다. [SpringSpigotBootstrapper](https://github.com/CChuYong/spigot-spring/blob/main/bukkit-api/src/main/kotlin/chuyong/springspigot/SpringSpigotBootstrapper.kt)

선택적 Boot adapter보다 큰 실행 환경이며, classloader 내부 필드·`JavaPlugin.isEnabled` 등의 reflection과 Spigot용 javaagent에 의존한다. [Java bootstrapper](https://github.com/CChuYong/spigot-spring/blob/main/bukkit-bootstrapper/src/main/java/kr/chuyong/SpigotSpringBootstrapper.java) 이는 단일 플러그인이 스스로 소유하는 얇은 Spring 컨텍스트라는 요구와 맞지 않는다. 최신 Paper에서 이 내부 접근이 동작한다는 근거도 확인하지 못했다.

### SpringSpigot (Spliterash)

플러그인마다 자신의 `context`를 생성·종료하고, `@SpigotListener`/`@SpigotCommand`와 YAML property source를 제공한다. `@PreDestroy` 사용과 컨텍스트 reload도 설명한다. [README](https://github.com/Spliterash/SpringSpigot/blob/master/README.md), [플러그인 lifecycle](https://github.com/Spliterash/SpringSpigot/blob/master/src/main/java/ru/spliterash/springspigot/init/SpringSpigotPlugin.java)

listener는 destruction post processor가 `HandlerList.unregisterAll(listener)`를 실행한다. 반면 command initializer는 등록만 하고 대응 destruction을 구현하지 않는다. [listener teardown](https://github.com/Spliterash/SpringSpigot/blob/master/src/main/java/ru/spliterash/springspigot/listener/SpigotListenerInitializer.java), [command 등록](https://github.com/Spliterash/SpringSpigot/blob/master/src/main/java/ru/spliterash/springspigot/commands/SpigotCommandInitializer.java) 좋은 생명주기 선례가 있으나, Boot 2 기반 별도 제공 플러그인·장기 미갱신·전체 teardown 불확실성 때문에 현 요구의 대체재는 아니다.

### mcspring-boot

Picocli 명령, 자동 listener 등록, `@Synchronize`, Bukkit main-thread scheduler, session, 자체 SpEL `@Authorize`를 제공한다. [README](https://github.com/Alan-Gomes/mcspring-boot/blob/master/README.md) `@Authorize`는 이 프로젝트의 자체 권한 aspect이며 Spring Security 연동이라는 뜻은 아니다. [SecurityAspect](https://github.com/Alan-Gomes/mcspring-boot/blob/master/src/main/java/dev/alangomes/springspigot/security/SecurityAspect.java)

`SpringSpigotBootstrapper.initialize(plugin, applicationClass)`가 Boot `SpringApplicationBuilder`를 사용하고 컨텍스트를 반환한다. [bootstrapper](https://github.com/Alan-Gomes/mcspring-boot/blob/master/src/main/java/dev/alangomes/springspigot/SpringSpigotBootstrapper.java) 기능 선례는 풍부하지만 마지막 기본 브랜치 코드가 2020년이며 Boot 2.1/Spigot 1.13 기준이라 현 프로젝트용 도입 후보보다 설계 참고 자료에 가깝다.

### Springlify

`SpringlifyBukkitPlugin` + `@SpringlifyApplication(springApplicationClass=...)`가 컨텍스트를 시작·종료한다. 실제 초기화는 Boot `SpringApplicationBuilder`를 사용한다. [plugin class](https://github.com/ii1818nn/springlify/blob/main/springlify-starter/springlify-starter-bukkit/src/main/java/dev/temez/springlify/starter/plugin/SpringlifyBukkitPlugin.java), [initializer](https://github.com/ii1818nn/springlify/blob/main/springlify-starter/springlify-starter-commons/src/main/java/dev/temez/springlify/starter/initializer/SpringlifyInitializerImpl.java)

플랫폼 공통부/Bukkit/Velocity 분리, 종료 전 Spring event, listener 등록·destruction 해제가 있다. command executor 등록 post processor에는 대응 destruction이 없다. [listener lifecycle](https://github.com/ii1818nn/springlify/blob/main/springlify-platform/springlify-platform-bukkit/src/main/java/dev/temez/springlify/platform/initializer/EventHandlerInitializer.java), [command 등록](https://github.com/ii1818nn/springlify/blob/main/springlify-platform/springlify-platform-bukkit/src/main/java/dev/temez/springlify/platform/initializer/CommandHandlerInitializer.java), [종료 전 event](https://github.com/ii1818nn/springlify/blob/main/springlify-starter/springlify-starter-commons/src/main/java/dev/temez/springlify/starter/initializer/event/ContextPreShutdownEvent.java) 구조적 참고 가치가 있으나 Boot 비필수 조건과 최근 지원 요구에는 맞지 않는다.

### ThomasOM starter

플러그인 인스턴스 필드에 새 `AnnotationConfigApplicationContext`를 보관하고 disable 시 닫는다. Boot 이름과 달리 시작 자체는 순수 Spring 방식이지만 POM이 Boot starter를 필수 의존한다. [entrypoint](https://github.com/ThomasOM/spring-boot-starter-bukkit/blob/master/src/main/java/dev/thomazz/spring/bukkit/SpringBukkitJavaPlugin.java)

`@PluginListener`는 등록·해제하며, `@PluginCommand`는 Bukkit `CommandExecutor` 대신 ACF `BaseCommand`를 요구하고 등록·해제한다. [listener processor](https://github.com/ThomasOM/spring-boot-starter-bukkit/blob/master/src/main/java/dev/thomazz/spring/bukkit/listener/PluginListenerBeanPostProcessor.java), [command processor](https://github.com/ThomasOM/spring-boot-starter-bukkit/blob/master/src/main/java/dev/thomazz/spring/bukkit/command/PluginCommandBeanPostProcessor.java) 종료 책임은 좋은 참고다. 다만 현재 entrypoint는 라이브러리의 `SpringBukkit` 클래스만 등록하므로 소비 플러그인의 application class를 등록·scan하는 연결도 추가 확인·수정이 필요하다. 보관된 snapshot이고 라이선스도 확인하지 못했다.

## 최근 후보

### Vital

계속 개발된 실제 Spring Boot 기반 통합 프레임워크다. plugin metadata/설정 생성용 annotation processor·Gradle plugin·loader, listener·commands, 모듈 enable/disable, 설정·다중 플랫폼 기능을 갖췄다. [README](https://github.com/mike-neumann/vital/blob/main/README.md), [VitalPlugin](https://github.com/mike-neumann/vital/blob/main/vital-core/src/main/kotlin/dev/vitalframework/VitalPlugin.kt), [VitalCoreModule](https://github.com/mike-neumann/vital/blob/main/vital-core/src/main/kotlin/dev/vitalframework/VitalCoreModule.kt)

Java 플러그인 사용 예제도 있으므로 Kotlin 구현 자체를 제외 사유로 삼을 필요는 없다. 다만 `SpringApplicationBuilder`와 자체 loader·metadata·`VitalPlugin`/`VitalListener`/`VitalCommand` 모델을 채택해야 한다. Boot 비필수 어댑터보다는 애플리케이션 프레임워크 선택에 해당한다. Java 25/Paper 26.3 지원·실행 증거와 완전한 컨텍스트별 rollback/teardown은 이번 조사에서 확인하지 못했다.

### mainframe

현대 Paper lifecycle API와 Spring을 연결하는 최근 구현이다. 플러그인별 컨텍스트를 enable에서 만들고 disable에서 닫으며, Gradle plugin이 `@SpringBootApplication` 클래스를 찾아 메타데이터와 Paper main class를 설정한다. [MainframePlugin](https://github.com/vjh0107/mainframe/blob/main/platform-paper/src/main/java/kr/junhyung/mainframe/platform/paper/plugin/MainframePlugin.java), [Gradle Paper plugin](https://github.com/vjh0107/mainframe/blob/main/gradle-plugin/src/main/kotlin/kr/junhyung/mainframe/gradle/MainframePaperPlugin.kt)

Bukkit `@EventHandler`가 붙은 listener bean을 등록하고, Paper `LifecycleEventHandler<RegistrarEvent<Commands>>` bean을 commands lifecycle에 연결한다. 전자는 BeanPostProcessor 등록만 있고 자체 destruction 해제는 없다. [EventHandlerRegistrar](https://github.com/vjh0107/mainframe/blob/main/platform-paper/src/main/java/kr/junhyung/mainframe/platform/paper/plugin/EventHandlerRegistrar.java), [LifecycleEventHandlerRegistrar](https://github.com/vjh0107/mainframe/blob/main/platform-paper/src/main/java/kr/junhyung/mainframe/platform/paper/plugin/LifecycleEventHandlerRegistrar.java)

주의할 구체적 정책은 Spring 시작 실패 시 `Runtime.getRuntime().halt(...)`로 **JVM 전체를 즉시 종료**한다는 점이다. 이는 개별 플러그인의 시작 실패를 처리할 작은 라이브러리의 계약과 다르다. Boot 4 필수 의존, 자체 배포 저장소, 현재 라이선스 미확인까지 고려하면 직접 채택보다 Paper lifecycle/스레드 경계 설계 참고에 적합하다. optional transaction guard도 구현되어 있다. [transaction listener](https://github.com/vjh0107/mainframe/blob/main/platform-paper/src/main/java/kr/junhyung/mainframe/platform/paper/transaction/PrimaryThreadTransactionExecutionListener.java)

## 이름이 비슷하지만 대체재가 아닌 후보

- `paper-spring`: 이 이름만으로는 공식 Minecraft Spring adapter 저장소·artifact를 특정하지 못했다. 존재하지 않는다고 단정하지 않는다. URL이나 Maven 좌표 없이 실재 후보로 가정하지 않는다.
- [RookieCuzz/BukkitSpring](https://github.com/RookieCuzz/BukkitSpring/blob/main/README.md)은 자체 `com.cuzz...ApplicationContext`와 자체 component annotation을 제공하는 IoC/DI 컨테이너다. Spring Framework 통합 라이브러리로 분류하지 않았다.
- [TR4E/Foundation](https://github.com/TR4E/Foundation/blob/master/README.md)은 Java 25 Paper 프레임워크와 Spring 모듈을 함께 갖고 있으나 Paper는 자체 Injector에 연결되고 Spring 모듈은 servlet/web 기능이다. 동일 저장소에 두 기능이 있다고 Paper–Spring bridge인 것은 아니다.
- [HQFramework](https://github.com/HQService/HQFramework/blob/main/gradle.properties)는 최근 활동이 있으나 DI는 Koin, persistence는 Exposed 기반이다. 실제 Spring Framework를 사용한다는 요구의 대체재로 비교하지 않았다.
- [Bukkit-Spring/Spring-Api](https://github.com/Bukkit-Spring/Spring-Api/blob/master/pom.xml)는 Java 8/Bukkit 1.12.2/Boot 2.3.6 기준 제공 환경으로, 현재 조건에 더 가까운 후보는 아니었다.

## 적용 권고

1. **기존 라이브러리 즉시 채택은 보류하고, 독립 Spring Paper 라이브러리를 작은 Paper 어댑터로 만드는 방향에 근거가 있다.** 핵심 이유는 지원 기준·플러그인 소유 lifecycle·안전한 rollback/teardown·Boot 비필수 계약이다. 자체 DI 컨테이너나 명령 문법을 다시 만들 필요는 없다.
2. 핵심은 하나의 plugin context, 기존 plugin 객체의 bean 등록, annotation 기반 listener/명령 연결, 등록 ownership 기록, 시작 실패 rollback, disable 시 해제·close 순서, `spring.paper` YAML 계약이다. 기존 후보의 annotation 이름이 다르다는 사실만으로 새 라이브러리를 만들 이유가 되지는 않는다.
3. JPA/transactions/cache/security는 별도 선택 기능으로 두는 것이 적절하다. 이번 후보 조사만으로 해당 Spring 기능들이 Paper에서 자동으로 안전해진다고 볼 수 없다. 특히 표준 Spring Security와 자체 permission annotation을 혼동하지 않아야 한다. 선택 기능 부재보다 현재 핵심 lifecycle의 정확성이 우선이다.
4. 개발 서버 자동 다운로드·실행은 [jpenilla/run-task의 run-paper](https://github.com/jpenilla/run-task) 재사용이 유력하다. 공식 README는 `minecraftVersion(...)`, Gradle `runServer`, 플러그인 jar 자동 연결을 제공한다. 라이브러리 runtime에 다운로드 기능을 섞거나 동일 도구를 새로 구현할 필요는 없다. 후속 개발 도구 검증에서 run-paper 3.0.2로 Paper 26.3 build 244를 실제 다운로드하고 checksum을 확인했다. [검증 기록](../verification.md)에 근거와 아직 실행하지 않은 서버 기동 범위를 구분했다.

확인 한계: 모든 후보의 Java 25/Paper 26.3 빌드·실서버·클라이언트 동작은 미검증이다. 이번 문서는 소스 조사 범위이며, 소스에서 확인한 버전·생명주기 차이와 실행 결과를 구분했다. 허용된 격리 로컬 서버 실행을 통한 호환성 검증은 별도 단계다.
