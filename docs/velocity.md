# Velocity 호스트 연결

`spring-paper-velocity`는 Velocity의 native 플러그인 하나를 Spring 컨텍스트 하나에 연결한다.
Java 25·Velocity 4.2.1-SNAPSHOT build 43·Spring Framework 7.0.10과 실제
Spring Boot **4.1.2 core + autoconfigure**를 사용한다. Paper core는 포함하지 않는다.
공통 bootstrap은 `WebApplicationType.NONE`을 강제하며 Spring Web·Servlet API·웹 서버·starter를 넣지 않는다.
허용한 Boot core 자체의 미활성 Servlet/reactive adapter 클래스는 최종 JAR에도 포함된다.

## Ordinary 애플리케이션

별도 Gradle 프로젝트에서 host 하나를 선택한다.

```groovy
plugins { id 'org.blu3fishez.spring-velocity' }
version = '0.1.0-SNAPSHOT'
springVelocity {
    apiCoordinate = 'com.velocitypowered:velocity-api:4.2.1-SNAPSHOT'
    development {
        version = '4.2.1-SNAPSHOT'
        buildNumber = 43
    }
}
```

```java
import org.blu3fishez.springpaper.velocity.annotation.SpringVelocityApplication;

@SpringVelocityApplication(id = "gateway", name = "Gateway")
public class GatewayApplication {}
```

ordinary root는 Spring이 만들며 root 아래 `@Service`·`@ConfigurationProperties`를 발견한다.
native JSON descriptor의 공용 `SpringVelocityApplicationPlugin` bridge만 Guice가 만든다.
Gradle이 `velocity-plugin.json`과 자기 JAR의 `META-INF/spring-paper/application.properties`를 생성하므로
native `@Plugin`과 annotation processor를 root에 추가하지 않는다. 버전은 Gradle project.version이다.
`scanBasePackageClasses`/`configurationPropertiesScanBasePackageClasses`로 type-safe 범위를 지정한다.
root 어노테이션은 `VelocityHostConfiguration`도 import하므로 IDE가 실제 호스트 빈 타입을 따라갈 수 있다.

`help/tasks`에는 YAML이나 target이 필요 없다. `assemble`은 compile coordinate만 요구하고 실행
target·DB·proxy secret을 읽지 않는다. 결과는 `bootJar`가 아닌 flat plugin JAR 하나다.
`runVelocity`에는 명시 version/buildNumber가 필요하며 latest를 선택하지 않는다.
Paper packaging plugin은 같은 프로젝트에 함께 적용하지 않는다.

런타임과 forked pre-start는 자기 JAR와 native plugin dataDir의 선택적인 ConfigData를 읽는다.
native cwd는 암묵적으로 검색하지 않으며 external base가 packaged profile보다 우선한다.
환경 변수·JVM 프로퍼티·명시 location/import는 [공통 설정 계약](configuration.md)을 따른다.
`application.yml`의 `spring.velocity.server` typed map에서 `velocity.toml`을 준비하며 boolean·정수·list와
`servers.try` 순서를 보존한다. dotted key에는 `'[island.test]'` 같은 Boot bracket 표기를 쓴다.

`prepareVelocityServer`는 기존 forwarding-secret-file 또는 실효 native secret 환경변수를 사용하며
새/빈 run 디렉터리만 소유한다. hash drift를 감지하고 기존 미소유 파일에는 계획과 민감값을 가린
`native-proposal.json`만 출력한다. plan-only에서는 secret source를 열지 않고 run도 중단한다.
shared secret 생성/회전·기존 proxy 원격 등록은 수행하지 않는다. 세부 native YAML·경로·secret 전달과
Paper backend 연결은 [ordinary 최소 예제](../examples/boot-velocity-plugin/README.md)를 따른다.

## 기존 native 진입점 호환

기존 native `@Plugin`·Guice 생성자와 명시 Configuration도 같은 Boot bootstrap으로 위임한다.
이 경로에는 ordinary root scan·ConfigData 탐색을 자동 적용하지 않는다.
새 Gradle 플러그인의 legacy `@Plugin` assemble은 지원하지만 새 `prepareVelocityServer`/`runVelocity`는
ordinary root 전용이다. 기존 native 예제의 수동 빌드·실행 경로는 그대로 유지한다.

```gradle
implementation 'org.blu3fishez.springpaper:spring-paper-velocity:0.1.0-SNAPSHOT'
compileOnly 'com.velocitypowered:velocity-api:4.2.1-SNAPSHOT'
annotationProcessor 'com.velocitypowered:velocity-api:4.2.1-SNAPSHOT'
```

## 기존 helper와 실제 호스트 빈

```java
private final SpringVelocityApplication application;

@Inject
public MyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
    application = new SpringVelocityApplication(this, proxy, logger, directory, MyConfiguration.class,
        context -> context.getEnvironment().setActiveProfiles("production"));
}

@Subscribe public EventTask initialize(ProxyInitializeEvent event) { return application.initialize(); }
@Subscribe public EventTask shutdown(ProxyShutdownEvent event) { return application.shutdown(); }
```

initializer는 표준 `ApplicationContextInitializer<ConfigurableApplicationContext>`다. 실제 호스트
singleton을 연결한 뒤 refresh 전에 실행되므로 Environment·property sources·profile·추가 빈 정의를
설정할 수 있다. 생성자에서 서버 API를 사용하거나 별도 ApplicationContext를 만들지 않는다.

```java
@Configuration(proxyBeanMethods = false)
@Import(VelocityHostConfiguration.class)
@ComponentScan(basePackageClasses = MyConfiguration.class)
class MyConfiguration {}
```

이 import는 `ProxyServer velocityProxyServer`, `org.slf4j.Logger velocityLogger`,
`Path velocityDataDirectory`, `Object velocityPlugin`의 실제 `@Bean` 정의를 제공한다.
IDE 전용 placeholder나 복제 호스트가 아니다. 런타임에서는 생성자로 받은 동일 객체를 연결하고
Spring 초기화·프록시·종료 콜백을 적용하지 않는다. 이 네 이름은 예약되어 있다.
경로나 소유자를 구분해서 주입할 때 `@Qualifier(VelocityHostConfiguration.DATA_DIRECTORY_BEAN_NAME)`
또는 `PLUGIN_BEAN_NAME`을 사용한다. IDE의 타입 연결도 이 루트 구성을 기준으로 한다.

## 활성 입력과 native 동작

```java
@VelocityEventListener
class LoginListener {
    @Subscribe(priority = 20, async = false)
    public EventTask login(LoginEvent event) { /* native EventTask/Continuation 계약 */ return null; }
}

@VelocityCommand(value = "guide", aliases = "helpme")
class GuideCommand implements SimpleCommand {
    public void execute(Invocation invocation) { /* ... */ }
}
```

두 어노테이션은 Spring component이며 `@Bean` 메서드에도 사용할 수 있다. 일반 scan·`@Import`·
`@Conditional`·profile로 활성 입력을 정한다. 새 기능을 추가할 때 라이브러리나 중앙 목록에 타입을
나열하지 않는다. 입력은 eager singleton이어야 한다. 비활성 모드를 `@Lazy`로 숨기는 구성은 거절한다.
명령의 canonical alias와 secondary aliases에는 Spring property placeholder를 사용할 수 있다.
클래스의 `@VelocityCommand.value`는 기존과 같이 Spring 빈 이름도 지정한다. 표준 `@AliasFor`로
`Component.value`에 명시적으로 연결하므로 Spring 7의 폐기 예정 이름 추론에 의존하지 않는다.
중복·기존 native alias 충돌은 어떤 입력도 획득하기 전에 거절한다.

라이브러리는 활성 listener 객체를 `EventManager.register(plugin, listener)`에 그대로 전달한다.
native `@Subscribe`의 priority/order, async, EventTask, Continuation, 상속 처리를 재구현하지 않는다.
클래스 기반 Spring 프록시도 같은 프록시 객체를 등록하고 advice를 유지한다. native scanner가
메서드를 찾을 수 없는 JDK listener 프록시는 명확히 거절한다. 필요한 경우 클래스 프록시를 사용하거나
AOP를 위임 서비스에 둔다. 명령은 native `Command` 인터페이스를 통해 실행된다.

입력 등록 순서가 같은 native priority 안에서 게임 규칙을 대신하지 않는다. 필요한 이벤트 순서는
Velocity의 `@Subscribe`에 선언한다. 별도 범용 플랫폼 SPI나 새로운 lifecycle 어노테이션은 제공하지 않는다.

## 준비 상태·종료·경합

컨텍스트 생성·initializer·refresh·모든 Spring `SmartLifecycle.start`가 끝난 뒤 입력을 등록한다.
모든 등록에 성공한 뒤에만 `isReady()`가 true이며 Boot ready/ACCEPTING 이벤트 전에 입력을 활성화한다.
설정·스캔·빈 생성·lifecycle·입력 등록·ready listener 실패는
소유 입력과 컨텍스트를 정리하고 native initialize EventTask에 예외를 전달한다.

`shutdown()`을 호출하면 즉시 준비 상태를 내리고 새 입력 획득을 막으며, 이미 획득한 입력만 개별
해제한다. 반환한 native async EventTask는 진행 중인 refresh/등록과 직렬화한 뒤 컨텍스트를 닫는다.
반드시 native shutdown subscriber에서 그 task를 반환해야 정리가 기다려진다. 시작과 종료가 겹치면
준비 상태를 다시 열지 않으며 시작 task는 중단 예외로 끝날 수 있다. 반복 종료는 멱등적이다.

listener는 `unregisterListener(plugin, actualBean)`으로 해제하므로 같은 플러그인의 native 진입점이나
수동 등록을 일괄 해제하지 않는다. 명령은 획득한 `CommandMeta`로 해제해 나중에 다른 소유자가
대체한 alias를 보존한다. native 등록이 부분 변경 후 실패해도 그 시도까지 정리한다.
해제 하나가 실패해도 나머지 입력과 컨텍스트 종료는 시도하며 반환한 shutdown task가 실패를 전달한다.
native API가 실패한 해제를 성공한 것으로 보장하거나 외부 플러그인의 동시 alias 변경을 원자적으로
제어할 수는 없다.

서버 종료·입장 거절은 소비자 정책이다. 인증이 필요한 플러그인은 Spring listener가 생성되지 않은
실패도 막도록 호스트 진입점에서 `isReady()`를 확인한다. 특정 로그인 규칙·TEST 모드·FastLogin·
OAuth를 bootstrap에 넣지 않는다. 인증은 별도 `spring-paper-security-core`와 소비자 Provider/체인을
같은 컨텍스트에 조립한다.

## 표준 작업과 자원 소유권

일반 시작·정지는 `SmartLifecycle`, 자원 해제는 `@Bean(destroyMethod=...)`·`DisposableBean` 등
표준 Spring 계약을 사용한다. 입력은 Spring 종료보다 먼저 해제한다. `@EnableScheduling`과
소비자의 `TaskScheduler`·`ThreadPoolTaskScheduler`, `Executor`·`SimpleAsyncTaskExecutor`를
그대로 사용하며 별도 Velocity task framework를 만들지 않는다. 전용 보안 타임아웃 스케줄러
`paperSecurityScheduler`를 일반 업무 scheduler의 alias로 재사용하지 않는다.

Velocity에는 Paper main thread가 없다. native listener는 Velocity가 정한 호출 스레드를 유지한다.
bootstrap은 컨텍스트 생성부터 정리까지 plugin TCCL을 적용하고 호출자의 원래 로더를 복원한다.
소비자의 비동기 executor가 plugin 클래스/리소스를 탐색한다면 표준 ThreadFactory/TaskDecorator에서
같은 로더를 명시한다. native 이벤트 콜백 전체를 감싸거나 raw target을 우회 호출하지 않는다.

## 검증과 공식 구현 근거

[ordinary 소비 예제](../examples/boot-velocity-plugin/README.md)는 새 root·typed properties·Gradle
플러그인·별도 native 준비 경로를 보여 준다. [기존 소비 예제](../examples/velocity-plugin/README.md)는 native 진입점, 실제 호스트 주입,
새 listener/command의 자동 등록, 표준 `@Scheduled`와 scheduler 소유권을 보여 준다.
API 호스트 자동 테스트는 반환한 실제 EventTask를 실행해 준비·시작 실패·종료 전 입력 해제·
시작 전/refresh 중/native 등록 중 종료·CGLIB advice·alias 소유권·스케줄러 종료를 검증한다.

최종 ordinary JAR은 고정 build 43의 실제 JSON loader→PluginClassLoader→Guice→공통 bridge→실제
Boot 경로로 검증한다. 두 loader의 own metadata/profile 분리·service/typed properties·준비 상태·정상
종료·host 소유권과 implicit cwd 제외를 확인한다. 재현은 기존 native JAR을 사용하며 다운로드하지 않는다.

```sh
python3 spring-paper-velocity/src/nativeTest/verify-native-application.py \
  --artifact examples/boot-velocity-plugin/build/libs/spring-velocity-boot-example-0.1.0-SNAPSHOT.jar
```

이 fixture는 controlled host bindings와 공개 EventTask 직접 실행이다. native plugin-manager의 이벤트
dispatch, proxy 프로세스 기동, client 접속·forwarding을 검증한 결과가 아니다.
`python3 scripts/verify-published-consumer.py`는 included build 없는 새 Velocity marker·전이 의존성·
비웹 그래프·metadata 병합과 원본 Boot core adapter bytecode 출처를 별도로 검증한다.

2026-11-11의 별도 격리 검증에서는 기존 native helper와 정확한 Velocity build 43·Maven 발행물로 실제 시작·명령·별칭·정상
종료를 확인했다. 첫 실행은 60초 Scheduled 로그를, alias 수정 후 두 번째 실행은 해당 Spring
경고의 소멸을 확인했다. [실행·테스트 수와 범위](verification.md#2026-11-11-velocity-호스트-어댑터)를
참고한다. 이는 Boot 전환 후의 proxy 프로세스 실행 결과가 아니다. 기존 개발 서버는 변경하지 않았으며
실제 클라이언트·Mojang 인증 검증은 포함하지 않는다.

설계 전에 확인한 공식 문서·소스:

- [Velocity 이벤트](https://docs.papermc.io/velocity/dev/event-api/), [명령](https://docs.papermc.io/velocity/dev/command-api/), [scheduler](https://docs.papermc.io/velocity/dev/scheduler-api/): native listener/task·명령 메타데이터와 비동기 스레드 계약.
- Velocity 4.2.1-SNAPSHOT의 배포된 API sources JAR: EventTask·EventManager·CommandManager·CommandMeta·Subscribe의 실제 컴파일 계약.
- [VelocityEventManager](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/event/VelocityEventManager.java): 클래스 계층의 Subscribe 탐색과 등록한 객체를 통한 호출, 플러그인/리스너 identity 기반 해제.
- [VelocityCommandManager](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/command/VelocityCommandManager.java): `unregister(CommandMeta)`의 metadata 조건부 alias 제거.
- [Spring AbstractApplicationContext](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java), [DefaultLifecycleProcessor](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/support/DefaultLifecycleProcessor.java): 컨텍스트 TCCL·refresh 실패 정리·표준 lifecycle 시작/정지.
- [ThreadPoolTaskScheduler](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.java): Spring 소유 scheduler의 지연 작업 취소·executor 종료.
