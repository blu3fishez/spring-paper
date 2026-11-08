# Spring Paper

Spring Framework를 Paper 플러그인에 연결하는 독립 라이브러리다. Spring Boot와 별도 제공 플러그인을 요구하지 않는다. Paper가 만든 플러그인 인스턴스와 플러그인별 하나의 컨텍스트를 사용한다.

이 저장소는 초기 개발 단계다. Maven Central·Gradle Plugin Portal 공개 배포와 라이선스 선정은 아직 진행하지 않았다. 현재는 included build 또는 로컬 Maven 배포로 사용한다.

## 조사와 범위

[기존 라이브러리 비교](docs/research/existing-libraries.md)에 8개 Spring 후보의 소스·유지보수·종료 계약을 정리했다. 확인한 후보에 현재 요구를 충족하는 즉시 도입 대상이 없어 제작했다. 개발 서버 다운로드·실행은 [run-paper](https://github.com/jpenilla/run-task)를 재사용한다.

현재 검증 기준은 Java 25, Paper 26.3 build 244, Spring Framework 7.0.10이다. Data·Security·Hibernate 조합은 [공식 의존성 조합](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom)을 참고해 직접 고정했다. Boot 코드를 실행하거나 Boot starter를 추가하지 않는다.

## 진입점

```java
@SpringPaperPluginApplication(
    name = "MyPlugin",
    configurations = MyPluginConfiguration.class
)
public final class MyPlugin extends SpringPaperPlugin {}
```

공통 부모가 `onEnable/onDisable`을 연결한다. 어노테이션 속성에서 이름·설명·작성자·웹사이트·API 버전·load·depend·softDepend·설정 리소스를 선언한다. `main`은 진입점에서, `version`은 Gradle 프로젝트 버전에서 가져와 `plugin.yml`을 생성한다.

진입점 어노테이션 자체는 Spring component가 아니다. Paper가 만든 객체를 컨텍스트에 등록하며, Spring이 `JavaPlugin`을 다시 생성하지 않는다. 기능별 설정에서 제한된 스캔·`@Import`·`@Bean`을 사용한다. 서버 역할·인증 모드별 구현을 전체 패키지 스캔으로 동시에 등록하지 않는다.

## 이벤트와 명령

```java
@PaperEventListener
public final class JoinListener implements Listener {
    private final WelcomeService welcome;
    public JoinListener(WelcomeService welcome) { this.welcome = welcome; }

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

이벤트는 Bukkit `@EventHandler` 우선순위·ignoreCancelled와 원래 호출 스레드를 유지한다. 핸들러는 public void 단일 Event 인자 메서드다. 프록시가 숨긴 핸들러를 원본 객체로 우회 호출하지 않는다. AOP는 위임 서비스에 두거나 프록시에서 호출 가능한 인터페이스를 제공한다.

## 사용자 정의 시작·종료

```java
@Service
public final class GuideLifecycleService {
    @PaperStart(phase = 0)
    public void start() { /* 발전과제 준비, 기존 상태 복구, 반복 작업 시작 */ }

    @PaperStop
    public void stop() { /* 반복 작업 취소, 미확정 기록 flush */ }
}
```

빈마다 public void 무인자 시작·종료 메서드를 하나씩 제공할 수 있다. 낮은 phase부터 시작하고, 같은 phase는 빈 이름순이다. 종료는 실제 시작 시도 순서의 역순이며 실패한 시작의 부분 정리도 호출한다. 다른 빈의 시작 완료가 필요하면 phase를 명시한다. `@DependsOn`은 Spring 빈 생성 순서이며 이 훅의 순서를 대신하지 않는다.

동일 빈의 `@PaperStop`과 `@PreDestroy`/`DisposableBean`을 중복 사용하지 않는다. `@PaperStop`이 있으면 AutoCloseable의 추론 종료를 중복 실행하지 않는다. 정리는 부분 초기화와 중복 호출에 안전해야 한다.

훅이 소유하는 자원은 start/ready 이후에 획득한다. 아직 시작을 시도하지 않은 훅은 종료 때 호출하지 않는다. 컨텍스트 생성 중 획득할 DB·네트워크 자원은 별도 Spring 빈으로 두고 Spring의 정상 destruction 계약으로 정리한다.

실행 순서는 설정 로딩 → 작업자 컨텍스트 준비·인프라 초기화 완료 대기 → 메인 스레드 사용자 start → 이벤트·명령 연결 → `PaperApplicationReadyEvent`다. 종료는 입력 연결 해제 → 사용자 stop → 컨텍스트와 인프라 정리다. 부분 실패에서도 이미 만든 자원을 정리하며 서버 JVM 전체를 종료하지 않는다.

일반 Spring `@PostConstruct`와 `SmartLifecycle`은 컨텍스트 준비 작업자에서 실행될 수 있다. Paper 상태 변경은 `@PaperStart/@PaperStop`, ready 이벤트 또는 `PaperTasks.main`에서 수행한다.

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

| 모듈 | 제공 기능 | 활성화 |
|---|---|---|
| `spring-paper-data-jpa` | Spring Data JPA 저장소, Hibernate, 트랜잭션, Hikari 기본 구성, 기존 자원 재사용 | `spring.paper.jpa.enabled` |
| `spring-paper-cache` | Spring Cache, Caffeine 기본 제공자, 커밋 뒤 캐시 변경, 기존 CacheManager 재사용 | `spring.paper.cache.enabled` |
| `spring-paper-security` | 메서드 인가, 호출별 Paper 사용자·권한 문맥, 거절 처리와 문맥 복원 | `spring.paper.security.enabled` |

모듈 JAR 추가와 YAML enabled가 모두 필요하다. 비활성 모듈은 컨텍스트에 구성되지 않는다. JPA의 기본 DDL 정책은 validate다. 예제의 create-drop은 격리된 H2 검증 데이터 전용이다. 운영 MariaDB 스키마는 기존 마이그레이션으로 관리한다.

`@Transactional` 서비스는 `PaperTasks.async`에서 호출한다. 기본 JPA 트랜잭션 매니저는 Paper 메인 스레드의 트랜잭션 시작을 거절한다. 작업 결과를 메인 스레드에 반영할 때 플레이어 세션·원정·revision 재검증은 사용하는 게임의 책임이다.

Security는 기존 플랫폼 인증을 대체하지 않는다. 동기 명령·플레이어 이벤트 호출마다 권한을 캡처하고 finally에서 이전 문맥을 복원한다. 작업자 전환에는 Spring Security의 `DelegatingSecurityContextRunnable/Callable` 등 명시적 전달을 사용한다. 비동기 이벤트에서 변경 가능한 Player 권한을 읽지 않으며 자동 인증 문맥도 부여하지 않는다.

## 로컬 빌드·예제

```sh
./gradlew build
./gradlew -p examples/guide-plugin build
./gradlew -p examples/guide-plugin preparePaperServer
./gradlew -p examples/guide-plugin runServer
```

예제는 included build로 이 저장소의 라이브러리와 Gradle 플러그인을 사용한다. `runServer`는 지정한 Paper 빌드를 다운로드하고 예제 Shadow JAR을 연결한다. EULA는 사용자가 확인한 뒤 YAML에서 명시적으로 수락한다. 기본값은 false다.

예제 플러그인은 실제 Paper 커스텀 이벤트·콘솔 명령·JPA 저장/조회·캐시·메서드 인가를 검사하고 `SPRING_PAPER_SMOKE_OK` 및 plugins/SpringPaperExample/smoke-success.txt를 남긴다. 종료 훅은 smoke-stop.txt를 남긴다. 예제는 자동 검증용이며 실제 플레이어 기능이나 클라이언트 검증을 대신하지 않는다.

이 초기 라이브러리는 SimpleExtraction 도입 준비물이다. SimpleExtraction의 실제 Guice 제거·역할별 마이그레이션과 검증계 배포는 별도 후속 작업이며, 기존 프로젝트를 변경하지 않았다.
