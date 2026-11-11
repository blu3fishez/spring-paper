# Velocity 호스트 연결

`spring-paper-velocity`는 Velocity가 생성한 플러그인 하나를 Spring 컨텍스트 하나에 연결한다.
Java 25·Velocity API 4.2.1-SNAPSHOT·Spring Framework 7.0.10을 사용한다. Paper core, Spring Boot,
추가 DI 컨테이너를 포함하지 않는다. Velocity의 native `@Plugin`·Guice 생성자가 호스트 진입점을 소유한다.

```gradle
implementation 'org.blu3fishez.springpaper:spring-paper-velocity:0.1.0-SNAPSHOT'
compileOnly 'com.velocitypowered:velocity-api:4.2.1-SNAPSHOT'
annotationProcessor 'com.velocitypowered:velocity-api:4.2.1-SNAPSHOT'
```

## 진입점과 실제 호스트 빈

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
모든 등록에 성공한 뒤에만 `isReady()`가 true다. 설정·스캔·빈 생성·lifecycle·입력 등록 실패는
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

[최소 소비 예제](../examples/velocity-plugin/README.md)는 native 진입점, 실제 호스트 주입,
새 listener/command의 자동 등록, 표준 `@Scheduled`와 scheduler 소유권을 보여 준다.
API 호스트 자동 테스트는 반환한 실제 EventTask를 실행해 준비·시작 실패·종료 전 입력 해제·
시작 전/refresh 중/native 등록 중 종료·CGLIB advice·alias 소유권·스케줄러 종료를 검증한다.
실제 Velocity 프로세스나 클라이언트 접속 검증과 구분한다. 배포·프록시 기동은 실행하지 않았다.

설계 전에 확인한 공식 문서·소스:

- [Velocity 이벤트](https://docs.papermc.io/velocity/dev/event-api/), [명령](https://docs.papermc.io/velocity/dev/command-api/), [scheduler](https://docs.papermc.io/velocity/dev/scheduler-api/): native listener/task·명령 메타데이터와 비동기 스레드 계약.
- Velocity 4.2.1-SNAPSHOT의 배포된 API sources JAR: EventTask·EventManager·CommandManager·CommandMeta·Subscribe의 실제 컴파일 계약.
- [VelocityEventManager](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/event/VelocityEventManager.java): 클래스 계층의 Subscribe 탐색과 등록한 객체를 통한 호출, 플러그인/리스너 identity 기반 해제.
- [VelocityCommandManager](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/command/VelocityCommandManager.java): `unregister(CommandMeta)`의 metadata 조건부 alias 제거.
- [Spring AbstractApplicationContext](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java), [DefaultLifecycleProcessor](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/support/DefaultLifecycleProcessor.java): 컨텍스트 TCCL·refresh 실패 정리·표준 lifecycle 시작/정지.
- [ThreadPoolTaskScheduler](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.java): Spring 소유 scheduler의 지연 작업 취소·executor 종료.
