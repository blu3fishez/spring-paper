# Spring Paper

Spring Framework를 Paper 플러그인에 연결하는 독립 라이브러리다. Spring Boot와 별도 제공 플러그인을 요구하지 않는다. Paper가 만든 플러그인 인스턴스와 플러그인별 하나의 컨텍스트를 사용한다.

Velocity는 독립적인 [`spring-paper-velocity`](docs/velocity.md) 모듈로 연결한다. native Guice 진입점은
유지하고 initialize/shutdown EventTask와 실제 호스트 빈·활성 입력 등록·정리 소유권을 제공한다.
Paper 모듈 없이 사용하는 [최소 예제](examples/velocity-plugin/README.md)가 있다.

이 저장소는 초기 개발 단계다. Maven Central·Gradle Plugin Portal 공개 배포와 라이선스 선정은 아직 진행하지 않았다. 현재는 included build 또는 로컬 Maven 배포로 사용한다.

## 조사와 범위

[기존 라이브러리 비교](docs/research/existing-libraries.md)에 8개 Spring 후보의 소스·유지보수·종료 계약을 정리했다. 확인한 후보에 현재 요구를 충족하는 즉시 도입 대상이 없어 제작했다. 개발 서버 다운로드·실행은 [run-paper](https://github.com/jpenilla/run-task)를 재사용한다.

현재 검증 기준은 Java 25, Paper 26.3 build 244, Spring Framework 7.0.10이다. Data·Security·Hibernate 조합은 [공식 의존성 조합](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom)을 참고해 직접 고정했다. Boot 코드를 실행하거나 Boot starter를 추가하지 않는다.

[Spring 내부 구현과 확장 지점](docs/configuration.md#spring-내부-구현에서-확인한-확장-계약)에 컨텍스트·프록시·종료·트랜잭션·캐시의 책임을 정리했다. 발생한 문제의 원인은 해당 구현 옆의 한국어 주석과 [검증 기록](docs/verification.md#막혔던-지점과-코드-주석)에 남겼다.

복사해서 쓸 작은 업무는 [business-plugin](examples/business-plugin/README.md)을 먼저 본다.
[전후 비교·검증](docs/usability-contracts.md), [실행 완료/취소](docs/execution-contracts.md), [기본/전용 빈](docs/default-beans.md),
[선택 기능](docs/optional-features.md)의 지원 계약을 사용한다.
모듈별 책임·확장 경계와 이번 재현 결과는 [책임·사용성 점검](docs/architecture-audit.md)에 정리했다.

## 진입점

```java
@SpringPaperPluginApplication(
    name = "MyPlugin",
    configurations = MyPluginConfiguration.class
)
public final class MyPlugin extends SpringPaperPlugin {}
```

공통 부모가 `onEnable/onDisable`을 연결한다. 어노테이션 속성에서 이름·설명·작성자·웹사이트·prefix·API 버전·load·depend·softDepend·설정 리소스를 선언한다. `permissions = @PaperPermission(...)`으로 명령 밖에서도 사용하는 권한을 선언할 수 있다. `main`은 진입점에서, `version`은 Gradle 프로젝트 버전에서 가져와 `plugin.yml`을 생성한다.

기존 호스트 설정을 캡처해야 하면 `initializers = ExistingSettingsInitializer.class`로 Spring 표준 `ApplicationContextInitializer<ConfigurableApplicationContext>`를 제공한다. 호스트 singleton 등록 후, 컨텍스트 refresh 전에 Paper 메인 스레드에서 실행된다. DB 연결 등 준비 작업은 이후 worker refresh의 일반 빈 초기화에 둔다.

진입점 어노테이션 자체는 Spring component가 아니다. Paper가 만든 객체를 컨텍스트에 등록하며, Spring이 `JavaPlugin`을 다시 생성하지 않는다. 기능별 설정에서 제한된 스캔·`@Import`·`@Bean`을 사용한다. 서버 역할·인증 모드별 구현을 전체 패키지 스캔으로 동시에 등록하지 않는다.

애플리케이션 구성에 코어 구성을 명시하면 IDEA의 Spring 모델도 호스트 빈의 타입을 인식한다.

```java
@Configuration(proxyBeanMethods = false)
@Import(PaperCoreConfiguration.class)
public class MyPluginConfiguration {
    // 기능별 @Import, @ComponentScan, @Bean
}
```

`JavaPlugin`, `Server`, `Logger`, `PaperConfiguration`, `PaperServerInfo`, `PaperThreadAccess`의 빈 정의를 제공한다. bootstrap에서 자동 등록하는 동일 구성에 연결되며, 호스트 객체와 컨텍스트를 추가로 만들지 않는다.

일반 서비스는 이 애플리케이션 구성의 제한된 `@ComponentScan`에서 표준 `@Component`·`@Service`로 발견한다.
새 서비스마다 라이브러리의 타입 목록이나 등록 분기를 추가하지 않는다. 선택 모듈의 공개 구성은 앱 루트에서
`@Import`하고, 활성화 조건은 제공자가 소유한다. IDE의 다중 모듈 컨텍스트 연결은 실제 루트 구성을 기준으로
맞춘다. IDE 가시성을 위해 공유 모듈이 구현 의존성을 `api`로 전파하지 않는다.
임의의 initializer·ServiceLoader 실행 결과 전체를 IDE가 정적으로 추론한다고 보장하지 않는다.

## 이벤트와 명령

```java
@PaperEventListener
@RequiredArgsConstructor
public final class JoinListener implements Listener {
    private final WelcomeService welcome;

    @EventHandler(priority = EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event) { welcome.joined(event.getPlayer()); }
}
```

```java
@PaperCommandExecutor(
    commands = "guide", aliases = "helpme",
    description = "Show the guide", permission = "myplugin.guide"
)
public final class GuideCommand implements CommandExecutor, TabCompleter {
    // 기존 Bukkit 명령·탭 완성 메서드. 같은 Spring 빈에 자동 연결된다.
}
```

명령 메타데이터를 `plugin.yml`에 중복 작성하지 않는다. Gradle 플러그인은 소비 프로젝트 및 의존한 기능 JAR의 어노테이션을 바이트코드로 읽어 명령과 권한 정의를 합친다. 다른 역할에서 같은 명령을 구현해도 메타데이터가 같으면 허용하며, 실제 활성 컨텍스트에 실행기가 둘이면 시작을 거절한다.

하나의 실행기가 서로 다른 usage·permission을 가진 명령을 처리하면 `@PaperCommandExecutor`를 반복한다. 같은 메타데이터를 공유하는 레이블은 기존 `commands` 배열을 사용한다.

소유 객체의 상태·콜백을 캡처한 리스너는 `@Bean @PaperEventListener` 메서드에서 기존 인스턴스를 반환할 수 있다. 클래스에 component 어노테이션을 붙일 필요가 없다. 순서 어노테이션 없이 등록할 수 있으며 기본값은 빈 이름순이다. 명시한 `PriorityOrdered`, `Ordered`, 타입·factory method의 `@Order`는 기본 순서를 정하는 호환 경로로 유지한다.

실제로 순서가 필요한 메서드만 `@PaperEventOrder(after = ArmorListener.class)` 또는 `before`를 선언한다. 같은 native HandlerList의 같은 우선순위 안에서 관계를 정렬한다. 상속 이벤트가 처리 목록을 공유하는 경우도 포함한다. Paper의 EventPriority와 모순되거나 순환하는 관계는 입력 등록 전에 거절한다. 비활성 역할의 리스너는 필수 의존성이 되지 않는다.

서로 다른 기능의 구현 사이에 의존성을 만들 수 없는 관계는 최상위 `@Configuration`에서 `PaperEventOrderContributor` 빈으로 제공한다. `PaperEventRelation(event, priority, before, after)`는 같은 native 목록과 우선순위 안에만 적용되며, 메서드 어노테이션과 함께 순환을 검증한다.

앱에서 리플렉션·프록시 타입을 다루지 않으려면 `PaperEventOrderConfigurer`를 제공한다.
`first(priority, ListenerType...)`는 해당 우선순위의 native 목록마다 앞부분에 실행할 그룹을 선언한다.
`firstBean`·`beforeBean`·`beforeBeans`는 소유 객체가 노출한 factory adapter의 Spring 빈 이름을 사용한다.
`before(event, priority, beforeType, afterTypes...)`로 나머지 관계를 선언한다. 활성 입력의 실제 메타데이터를
코어가 해석하며 기존 관계·어노테이션과 같은 그래프에서 순환을 검증한다.

공개 `Listener` 하위 인터페이스도 그룹 선택자로 쓸 수 있다. 구성은 실제로 공유하는 입력 단계의
계약만 참조하고, 각 기능이 구현한 리스너 목록을 중앙에 나열하지 않아도 된다. 같은 그룹 안에는
추가 순서를 만들지 않는다. `first`를 여러 번 호출하면 하나의 prefix에 이어 붙이므로 공유 prefix는
그 파이프라인의 구성 한 곳이 소유한다. 독립 기능의 관계는 기능별 Configuration의 configurer에서
`before`로 제공한다. 여러 configurer의 관계도 같은 native 목록·우선순위 그래프로 검증한다.

이벤트는 Bukkit `@EventHandler` 우선순위·ignoreCancelled와 원래 호출 스레드를 유지한다. 핸들러는 public void 단일 Event 인자 메서드다. 프록시가 숨긴 핸들러를 원본 객체로 우회 호출하지 않는다. AOP는 위임 서비스에 두거나 프록시에서 호출 가능한 인터페이스를 제공한다.

핸들러와 lifecycle 훅은 Java의 override 규칙을 따른다. 재정의한 메서드의 어노테이션만 적용하며 부모와 자식을 중복 등록하지 않는다. 재정의 메서드에서 어노테이션을 제거하면 부모의 어노테이션도 실행되지 않는다.

## 사용자 정의 시작·종료

```java
@Service
public final class GuideLifecycleService {
    @OnPaperMainThreadStartup
    public void start() { /* 발전과제 준비, 기존 상태 복구, 반복 작업 시작 */ }

    @OnPaperMainThreadShutdown
    public void stop() { /* 반복 작업 취소, 미확정 기록 flush */ }
}
```

이 두 어노테이션은 Spring Paper가 제공하는 **Paper 메인 스레드 전용 콜백**이며 Spring 표준
어노테이션이 아니다. Startup은 worker의 컨텍스트 준비 뒤·입력 등록 전에, Shutdown은 입력 해제 뒤·
worker drain 및 Spring 인프라 종료 전에 실행된다. DB·캐시·TaskScheduler 같은 일반 빈은 Spring이 관리한다.

순서가 무관한 콜백에는 관계나 숫자가 필요 없다. 기본 시작은 빈 이름순, 기본 종료는 실제 시작 시도의 역순이다.
부분 시작에 실패한 소유자도 정리하며, 아직 시작을 시도하지 않은 소유자는 실행하지 않는다.
종료만 있는 소유자는 빈 start 메서드 없이도 내부 활성화 순회에 참여한다.

필요한 관계는 표준 Spring 빈으로 선언한다. 타입·Qualifier·alias로 선택한 실제 소유자를 넘기므로
별도 빈 선택자나 다른 도메인 구현의 참조를 만들 필요가 없다.

```java
@Bean
PaperLifecycleRelation checkpointBeforeSessionCleanup(
        CheckpointOwner checkpoint, SessionOwner sessions) {
    return PaperLifecycleRelation.shutdownBefore(checkpoint, sessions);
}
```

`startupBefore(first, next)`와 `shutdownBefore(first, next)`는 독립적 관계다. 두 그래프의 순환과
잘못된 소유자·훅 참조를 어떤 콜백도 실행하기 전에 거절한다. 역할별 관계는 역할별 Configuration에 둔다.
한 소유자의 내부 정리 체인은 그대로 유지한다. `@DependsOn`은 빈 생성 의존성이며 Paper 준비 완료를 뜻하지 않는다.

기존 `PaperStart`·`PaperStop`과 phase/phaseString은 호환용으로 지원한다. 새 코드에는 명시적인 스레드/시점
어노테이션을 사용한다. 기존 숫자는 관계로 정해지지 않은 후보의 정렬에만 사용하며 명시적 관계가 우선한다.

동일 빈의 `@OnPaperMainThreadShutdown`과 `@PreDestroy`/`DisposableBean`을 중복 사용하지 않는다. `@OnPaperMainThreadShutdown`이 있으면 Spring의 명시적 `@Bean(destroyMethod=...)`와 AutoCloseable 추론 종료 대신 이 훅만 실행한다. 정리는 부분 초기화와 중복 호출에 안전해야 한다.

훅이 소유하는 자원은 start/ready 이후에 획득한다. 아직 시작을 시도하지 않은 훅은 종료 때 호출하지 않는다. 컨텍스트 생성 중 획득할 DB·네트워크 자원은 별도 Spring 빈으로 두고 Spring의 정상 destruction 계약으로 정리한다.

실행 순서는 설정 로딩·initializer → 작업자 컨텍스트 준비·인프라 초기화 완료 대기 → 메인 스레드 사용자 start → 이벤트·명령 연결 → `PaperApplicationReadyEvent`다. 종료는 입력 연결 해제 → 사용자 stop → Spring 주기 작업 생산 중단 → 메인 스레드 예약·청크 lease·native tick 작업 취소 → 코어 워커의 새 작업 차단·진행 작업 대기 → 컨텍스트와 인프라 정리다. 부분 실패에서도 이미 만든 자원을 정리한다. 기본 실패 정책은 해당 플러그인을 실패시키며, 소비자가 `SpringPaperPlugin.onApplicationStartupFailure`를 재정의해 정리 후의 서버 운영 정책을 선택할 수 있다.

일반 Spring `@PostConstruct`와 `SmartLifecycle`은 컨텍스트 준비 작업자에서 실행될 수 있다. Paper 상태 변경은 `@OnPaperMainThreadStartup/@OnPaperMainThreadShutdown`, ready 이벤트 또는 `PaperTasks.main`에서 수행한다.

## 예약 작업과 Paper 호스트 기능

일반 게임 서비스는 `JavaPlugin` 대신 필요한 빈을 생성자로 주입한다.
`Server`, `java.util.logging.Logger`, `PaperPluginInfo`는 현재 플러그인의 호스트·로거·불변 메타데이터다.
키 생성은 `PaperKeys`, tick 예약은 `PaperTickScheduler`, 공유 청크 유지 lease는 `PaperChunkTickets`가 소유한다.
기존 Bukkit config.yml을 유지하는 앱은 `PaperBukkitConfiguration.values()/save()`를 사용한다.
새 설정은 Spring Environment와 `PaperConfiguration`을 사용한다. JavaPlugin 빈은 호스트·기존 SDK 연결용으로 유지한다.

표준 `@EnableScheduling`을 설정 클래스에 선언하면 `@Scheduled`를 사용할 수 있다.
Paper 상태를 다루는 작업은 실행 스케줄러를 명시한다.

```java
@Scheduled(scheduler = PaperMainThreadTaskScheduler.BEAN_NAME,
        initialDelayString = "PT1S", fixedDelayString = "PT1S")
public void checkWorldCycle() {
    // Paper main thread; DB/network 작업은 PaperTasks.async로 보낸다.
}
```

`fixedDelay`는 실제 Paper 작업 완료 이후부터 계산한다. 서버가 멈춘 동안 반복 콜백을 계속 쌓지 않는다.
메인 전용 빈은 qualifier로 선택한다. 무지정 작업은 소비자의 기본 스케줄러를 사용하며, 없으면 코어의
지연 생성된 Spring 백그라운드 스케줄러를 사용한다. 소비자의 ScheduledExecutorService도 재사용한다.
Security는 별도 `paperSecurityScheduler`를 사용한다. 같은 이름의 소비자 빈으로만 명시적으로 교체한다.
한 번 실행할 예약은 `TaskScheduler.schedule(action, instant)`와 반환한 `ScheduledFuture.cancel(...)`을 사용한다.
취소는 대기 중인 Paper 콜백에도 전달되며, 실행 중인 서버 스레드를 interrupt하지 않는다. 서버 스레드에서 Future 완료를 기다리지 않는다.

서버 tick 수를 기준으로 하는 전투·애니메이션은 `PaperTickScheduler.runTaskLater/runTaskTimer`를 사용한다.
`PaperTickScheduler`의 표준 `Executor.execute`는 이미 메인 스레드여도 다음 tick으로 미룬다.
`PaperTasks.main`은 메인 스레드 호출이면 즉시 실행하고, `async`/`run`은 기존 플러그인 워커에서 실행한다.
청크 lease는 메인 스레드에서 획득·해제하며, 마지막 lease가 닫힐 때 자신이 추가한 native 티켓만 제거한다.

자세한 [예약·비동기 전환 예제와 선택 기준](docs/scheduling.md), [소비자 사용성 조사와 전후 비용](docs/productivity.md),
[기능별 실행 예제](examples/README.md)를 제공한다. `PaperTasks`는 표준 `AsyncTaskExecutor`이므로
`@EnableAsync`를 선택한 앱에서 `@Async("paperTasks")`로 재사용할 수 있다. 프록시를 거치는 독립 업무에만 사용한다.

## YAML과 개발 서버

```yaml
spring:
  paper:
    minecraft:
      version: '26.3'
      api-version: '26.3'
      api-coordinate: '26.3.build.244-stable'
      build: 244
      java-version: 25
    development:
      directory: run
      accept-eula: false
      jvm-args: ['-Xms512M', '-Xmx1G']
      server-properties:
        server-ip: '127.0.0.1'
        server-port: 25585
        online-mode: true
```

설정 루트는 합의한 `spring.paper`다. [전체 설정 계약](docs/configuration.md)을 참고한다. `PaperServerInfo`는 YAML 대상과 별도로 실제 Minecraft·Bukkit·서버·Java 버전을 제공한다.

## 선택적 Spring 생태계

Spring Paper는 Paper 호스트를 표준 Spring ApplicationContext에 연결한다. 기존 Spring 라이브러리는
의존성을 추가하고 같은 컨텍스트에 표준 Configuration·빈을 등록해 사용한다. 각 선택 모듈은
해당 생태계의 기본 구성과 Paper 연결을 제공한다. 어노테이션·저장소·인가 처리는 Spring 구현에 맡긴다.
Spring Boot 런타임과 starter 자동 구성은 사용하지 않는다.

| 모듈 | 제공 기능 | 활성화 |
|---|---|---|
| `spring-paper-data-jdbc` | 표준 JdbcClient·JdbcTemplate, 소비자의 DataSource·트랜잭션 매니저 사용 | `spring.paper.jdbc.enabled` |
| `spring-paper-data-jpa` | Spring Data JPA 저장소, Hibernate, 트랜잭션, Hikari 기본 구성, 기존 자원 재사용 | `spring.paper.jpa.enabled` |
| `spring-paper-data-pdc` | 엔티티·아이템·container의 선언형 record 매핑, 저장 타입 보존, 읽기 오류 구분 | `@EnablePaperPdcRepositories`; factory 자동 구성은 `spring.paper.pdc.enabled` |
| `spring-paper-cache` | Spring Cache, Caffeine 기본 제공자, 커밋 뒤 캐시 변경, 기존 CacheManager 재사용 | `spring.paper.cache.enabled` |
| `spring-paper-security` | 메서드 인가, 소비자 교체형 인증 문맥·거절 처리, 선택형 Paper 접속 어댑터 | `spring.paper.security.enabled` |
| `spring-paper-security-core` | 호스트 중립 인증 필터·세션·대기·결과 처리, Spring AuthenticationManager/Provider 연동 | Paper Security가 자동 import; 단독 소비는 `@Import(PaperConnectionSecurityConfiguration.class)` |
| `spring-paper-velocity` | native Velocity bootstrap, 실제 호스트 빈, 활성 listener/command 등록·해제 | native 진입점에서 `SpringVelocityApplication`에 위임 |

YAML 자동 구성에는 모듈 JAR과 enabled가 필요하다. PDC의 명시적인 Enable 어노테이션은 해당 구성에서 직접 활성화한다. JPA의 기본 DDL 정책은 validate다. 예제의 create-drop은 격리된 H2 검증 데이터 전용이다. 운영 MariaDB 스키마는 기존 마이그레이션으로 관리한다.

PDC 저장소의 [사용법과 저장 계약](docs/configuration.md#pdc)을 참고한다. 기존 키·UUID 문자열·숫자 타입을 선언으로 매핑하고, 아이템 변경 반영과 관련 없는 키 보존은 모듈이 맡는다. 게임 규칙·스키마 이관·엔티티 조회는 소비자가 소유한다.

Cache는 `org.springframework.cache.annotation`의 `@Cacheable`, `@CachePut`, `@CacheEvict`를
서비스 메서드에 직접 사용한다. 모듈이 `@EnableCaching`을 연결하고 Spring이 프록시·키·표현식·무효화를 처리한다.
소비자의 `CacheManager` 빈 또는 `CachingConfigurer`의 CacheResolver·KeyGenerator를 그대로 사용한다.
Caffeine names/spec은 기본 제공자를 선택할 때만 필요하다. [실제 Cacheable 사용 예제](examples/guide-plugin/src/main/java/org/blu3fishez/springpaper/example/service/NoteService.java)와
[표준 Spring 구성 연결](docs/configuration.md#cache)을 참고한다.

라이브러리는 Lombok 1.18.48을 컴파일 전용으로 사용한다. 소비 코드에서 생성자 생성을 쓰려면 같은 버전을 `compileOnly`와 `annotationProcessor`에 추가한다. 런타임 JAR에 Lombok을 포함하지 않는다. 단순 필드 대입을 생성하고 검증·키 생성·자원 획득이 있는 생성자는 유지한다.

`@Transactional` 서비스는 `PaperTasks.async`에서 호출한다. 기본 JPA 트랜잭션 매니저는 Paper 메인 스레드의 트랜잭션 시작을 거절한다. 작업 결과를 메인 스레드에 반영할 때 플레이어 세션·원정·revision 재검증은 사용하는 게임의 책임이다.

Security는 호출마다 인증 문맥을 설정하고 finally에서 이전 문맥을 복원한다. 기본 플랫폼 모드는 비동기 이벤트에서 Player 권한을 읽지 않으며, 접속 모드는 검증한 불변 세션 snapshot을 사용할 수 있다. 작업자 전환에는 Spring Security의 `DelegatingSecurityContextRunnable/Callable` 등 명시적 전달을 사용한다. 인증 체인 자체는 Authentication을 값으로 전달한다.

접속 인증은 [Spring Paper Security 확장 계약](docs/security.md)을 따른다. 소비자가 AuthenticationManager/Provider·필터 체인·세션 저장소·대기 처리기·결과 처리기·호출 인증 resolver를 빈으로 제공하면 각 기본 구현이 타입 기준으로 물러난다. 인증 대기의 의미와 UI는 소비자가 정한다. Paper 기본 접속 어댑터는 `spring.paper.security.connection.enabled: true`로 켜고, 자체 Paper/Velocity 어댑터는 호스트 타입이 없는 security-core를 호출한다.

## 로컬 빌드·예제

```sh
./gradlew build
./gradlew -p examples/guide-plugin build
./gradlew -p examples/guide-plugin preparePaperServer
./gradlew -p examples/guide-plugin runServer
```

`python3 scripts/verify-published-consumer.py`는 build/verification-repository에 Maven 배포물을 만들고 included build 없이 별도 소비 프로젝트를 빌드한다. 플러그인 marker와 모듈 의존성, 설정 캐시 재사용, SPI 병합과 호스트 API 제외를 확인한다. ~/.m2와 외부 배포 저장소에는 게시하지 않는다.

예제는 included build로 이 저장소의 라이브러리와 Gradle 플러그인을 사용한다. `runServer`는 지정한 Paper 빌드를 다운로드하고 예제 Shadow JAR을 연결한다. EULA는 사용자가 확인한 뒤 YAML에서 명시적으로 수락한다. 기본값은 false다.

예제 플러그인은 실제 Paper 커스텀 이벤트와 선후 관계·엔티티/아이템 PDC·콘솔 명령·JPA 저장/조회·캐시·메서드 인가와 소비자 Provider의 인증 대기·추가 증거·완료·정리를 검사하고 `SPRING_PAPER_SMOKE_OK` 및 plugins/SpringPaperExample/smoke-success.txt를 남긴다. 종료 훅은 smoke-stop.txt를 남긴다. 인증 증거는 합성 검증용이며 실제 Minecraft/Discord 인증기나 클라이언트 검증을 대신하지 않는다.

SimpleExtraction 도입은 별도 저장소의 [SIMEXT-90 PR](https://git.blu3fishez.org/tls-team/simple-extraction-plugin/pulls/254)에서 진행한다. 이 라이브러리의 검증 성공이 소비자의 실제 역할·인증 모드·검증계 접속 성공을 뜻하지 않는다.
