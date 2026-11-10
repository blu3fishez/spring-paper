# Spring Paper Security

소비자가 접속 인증·인증 대기·인가 흐름을 조립하는 프레임워크다. 특정 게임의 계정 규칙이나 FastLogin·Discord를 필수 구현으로 포함하지 않는다. Spring Boot·Servlet 컨테이너를 요구하지 않는다.

## 모듈과 책임

- spring-paper-security-core: Spring 인증/인가 계약, 비동기 필터 체인, 세션 전이·만료·종료. Bukkit/Velocity API 의존성이 없다.
- spring-paper-security: Spring Paper 입력 문맥, 선택형 Paper 접속 어댑터, 동기 snapshot과 기본 응답 처리. security-core를 포함한다.
- 소비자: 인증 증거의 검증, 필요한 인증 조건, 저장소 I/O·직렬화, 대기 UI·외부 OAuth 콜백과 플랫폼 효과.

Core를 단독 사용하는 Spring 컨텍스트에서는 `@Import(PaperConnectionSecurityConfiguration.class)`를 사용한다. 공통 모듈도 합의한 Java 25 대상이다. 기존 Velocity의 Java 21 bytecode 설정만 보고 runtime 호환을 가정하면 안 되며 도입할 프록시의 실제 JVM을 확인해야 한다. 이번 변경은 SimpleExtraction/Velocity runtime이나 배포 설정을 바꾸지 않았다.

## 소비자 확장 지점

| 계약 | 소비자가 구현·제공하는 내용 |
|---|---|
| Spring AuthenticationProvider / AuthenticationManager | 인증 수단·증거 검증. Manager가 없으면 등록한 Provider들로 ProviderManager 구성 |
| PaperSecurityFilter / PaperSecurityFilterChain | 적용 조건, 순서, 추가 검증, 인증 대기 또는 거절로 중단 |
| PaperSecuritySessionRepository | 비동기 세션 저장·복원·원자적 revision 갱신·무효화 |
| PaperAuthenticationEntryPoint | PENDING의 인증 요구 안내·대기 흐름 시작 |
| PaperSecurityResultHandler | 인증 완료·거절·시간 초과·접속 종료의 후속 처리 |
| PaperAuthenticationResolver | 명령·이벤트의 현재 인증 주체를 nonblocking snapshot으로 반환 |
| PaperAccessDeniedHandler | 메서드 인가 거절의 명령·탭 완성·이벤트 응답 |
| PaperConnectionAuthenticationConverter | Paper 입장 이벤트에서 검증 요청 token 추출 |
| PaperConnectionAdmissionHandler | Paper 입장 결과와 접속 후 인증 철회에 따른 플랫폼 처리 |

소비자 @Bean이 모두 파싱된 뒤 계약 타입으로 기본 구현을 제외한다. 빈 이름 재정의를 켤 필요가 없다. 여러 구현 중 하나를 선택하는 계약은 Spring의 @Primary를 사용한다. 필터 체인은 여러 개를 둘 수 있고 @Order 순서에서 처음 매칭한 체인 하나만 실행한다. 빈이 반환할 타입을 Object로 숨기지 말고 해당 계약 타입 또는 구현 타입으로 선언한다.

기본 저장소는 프로세스 로컬 메모리다. 기본 인증 대기/결과 처리기는 부작용이 없고, 빈 체인·미매칭·인가 규칙 누락·인가 판단 abstain은 거절이다. 저장소를 제공한다고 인증 조건이 자동으로 생기지 않는다.

## 필터 조립

아래 타입은 실제 제공하는 계약이다. additionalVerification은 소비자가 구현하고 빈으로 제공한 PaperSecurityFilter다. ACCOUNT_VERIFIED는 소비자 권한 이름의 예시이며 프레임워크의 고정 규칙이 아니다.

```java
@Bean
PaperSecurityFilterChain connectionSecurity(
        AuthenticationManager manager,
        PaperTasks tasks,
        @Qualifier("additionalVerification") PaperSecurityFilter additionalVerification) {
    return new PaperSecurity()
        .authenticationManager(manager, tasks)
        .addFilterAfter(additionalVerification, PaperAuthenticationFilter.class)
        .authorize((authentication, exchange) -> new AuthorizationDecision(
            authentication.get().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ACCOUNT_VERIFIED"))))
        .build();
}
```

- authenticationManager는 소비자 Manager를 지정한 Executor에서 호출한다. PaperTasks는 표준 Executor를 구현해 기존 plugin worker를 재사용한다.
- addFilter / addFilterBefore / addFilterAfter로 순서를 지정한다. anchor가 없거나 여러 개면 구성 오류다. JDK proxy의 anchor는 proxy에 노출된 타입을 사용한다.
- securityMatcher는 PaperSecurityRequest를 받는다. CONNECT/AUTHENTICATE/REVALIDATE 단계와 소비자 metadata로 구분할 수 있다.
- with(Customizer<PaperSecurity>)로 소비자 조립 함수를 적용할 수 있다. builder는 Spring AbstractSecurityBuilder의 한 번만 build하는 계약을 따른다.
- 필터는 next.next(exchange.withAuthentication(result))로 이어가거나 PENDING/DENIED/검증 완료 결정으로 중단할 수 있다. next는 한 번만 호출하며 request/session identity를 바꾸지 않는다.
- 입력 token의 isAuthenticated=true도 검증 증거로 바로 신뢰하지 않는다. 기본 인증 필터는 Manager를 호출하며 반환 token의 CredentialsContainer 자격 증명을 지운다. 소비자 token/principal도 공개 후 변경하지 않는다.
- ProviderManager는 첫 성공에서 멈춘다. Provider 두 개의 등록은 두 인증 모두 필수라는 뜻이 아니다. 필요한 증거·권한을 명시적으로 함께 검사하고, 다른 계정의 인증을 섞지 않는 규칙도 소비자가 구현한다.

동작하는 전체 소비 예제는 [ExampleSecurityConfiguration](../examples/guide-plugin/src/main/java/org/blu3fishez/springpaper/example/security/config/ExampleSecurityConfiguration.java)에 있다. 이 예제의 proof는 합성 smoke 검증 전용이다. 실제 Minecraft/Discord 인증기가 아니며, 전용 metadata가 없는 실제 접속 요청에는 매칭하지 않는다.

## 접속과 인증 대기

PaperConnectionSecurityService를 플랫폼 어댑터·명령·검증한 외부 콜백에서 호출한다.

```text
open(CONNECT 요청)
  → 세션 생성 → 필터 체인
  → AUTHENTICATED / PENDING / DENIED

authenticate(동일 접속의 새 증거)
  → 현재 세션·revision 확인 → 같은 정책으로 재평가

session(접속)       → 비동기 권위 저장소 조회
current(snapshot) → nonblocking 로컬 철회/revision 확인 (권위 조회·TTL을 대체하지 않음)
disconnect(접속)    → 즉시 로컬 차단 + 저장소/결과 처리 완료 확인
shutdown() / close() → 새 요청 차단 + 소유한 접속 정리
```

PaperSecurityConnection은 playerId, connectionId, name의 불변 접속 식별자다. UUID/이름 자체가 인증 증거는 아니다. 새 접속에는 새 connectionId를 만들고, 후속 콜백에는 기존 식별자를 그대로 사용한다. 같은 플레이어의 새 접속을 이전 접속의 결과로 수정하지 않는다.

PENDING은 소비자 PaperAuthenticationChallenge(type, parameters)를 담는다. 챌린지의 의미와 전달 방식은 소비자가 정한다. 최초 인증 증거를 보유할 수 있지만 이 상태는 게임 기능을 사용할 인증 완료 상태가 아니다. 기본 세션 resolver는 AUTHENTICATED만 노출한다.

PaperAuthenticationEntryPoint.commence는 안내를 시작한 뒤 stage를 신속하게 완료해야 한다. 사용자가 브라우저에서 인증할 때까지 이 stage를 열어 두지 않는다. 외부 콜백에서 nonce/state·대상 계정·현재 접속·만료·일회성 소비를 검증한 뒤 authenticate를 호출한다. 접속 종료 후의 콜백은 인증을 되살릴 수 없다. 접속을 끊고 계정 연동 후 재접속시키는 정책이라면 지속 계정 연동과 새 접속의 인증은 소비자가 각각 처리한다.

AUTHENTICATED 결정은 저장소 CAS 확인 뒤 공개한다. 결과 handler는 이미 결정한 상태의 효과를 처리한다. 추가 인증을 결과 handler에 숨기지 말고 필터에서 완료한다. handler 실패는 연결을 무효화하지만 외부 효과를 되돌리는 분산 트랜잭션을 제공하지 않는다. 처리기는 중복/부분 처리에 안전하게 구현한다.

## 저장소·경쟁·시간 초과

PaperSecuritySessionRepository의 각 메서드는 호출 스레드에서 즉시 stage를 반환해야 한다. DB/네트워크 작업은 저장소 어댑터의 worker에서 수행한다. 필터·대기/결과 처리기도 같은 비동기 계약을 따른다.

- create는 새 connectionId만 생성하고 이미 있으면 false다. 실패한 생성으로 다른 컨텍스트의 기존 row를 지우지 않는다.
- create=true 응답을 확인하기 전의 철회는 미확정 row를 임의로 삭제하지 않는다. 뒤늦게 true가 오면 기존 철회 상태를 유지하며 해당 row를 정리하고, false면 기존 row를 보존한다.
- compareAndSet은 전체 접속 식별자와 revision을 함께 검사하고 revision을 정확히 하나 증가시킨다. 만료된 expected는 실제 저장 시점에 거절한다.
- remove는 expected revision만, invalidate는 이 정확한 접속의 모든 revision을 제거한다. playerId만으로 다른 접속을 삭제하지 않는다.
- Redis 등을 구현한다면 서버 측 TTL/원자적 조건 검사까지 포함한다. 미리 읽은 시각만 검사하면 지연된 저장 요청이 만료 이후 인증을 복원할 수 있다.
- 자격 증명·SecurityContext·Player 객체를 저장하지 않는다. 소비자의 영속 모델에 맞춰 검증된 식별자·권한·접속 토큰과 필요한 metadata만 직렬화한다.
- 같은 접속의 동시 인증 작업은 거절하며, 재시도로 최초 pending deadline을 늘리지 않는다.

저장소 삭제가 멈추거나 실패해도 로컬 인증은 먼저 무효화하고 대기 중인 작업을 종료한다. PaperSecurityConnectionInvalidatedEvent는 이 로컬 철회를 뜻한다. PaperSecuritySessionChangedEvent의 terminal 상태도 원격 삭제 성공을 뜻하지 않는다. 삭제와 handler 완료는 disconnect/shutdown의 stage로 확인한다. 네트워크 장애·프로세스 강제 종료에 대비한 TTL/내구성 정책은 저장소 어댑터가 소유한다.

종료는 새 요청 차단과 모든 소유 연결의 로컬 철회·정리 ACK 예약을 한 admission 경계에서 끝낸 뒤,
관찰 이벤트·handler·저장소를 락 밖에서 호출한다. 관찰자의 재진입이나 동시 disconnect로 다른 연결의
handler ACK가 종료 결과에서 빠지지 않는다. 한 handler가 실패해도 나머지 연결과 저장소 삭제는 시도한다.
아직 응답하지 않은 create I/O 자체의 강제 완료·롤백은 disconnect/shutdown이 보장하지 않는다.
create가 실제로 커밋했지만 실패로 응답한 경우도 소유권이 확정되지 않으므로 다른 row를 삭제하여 복구하지 않는다.
이 불확정 커밋과 응답 없이 끝난 프로세스의 잔여 row는 저장소의 TTL·멱등성·조정 정책으로 처리한다.

비동기 구간의 Authentication은 값으로 전달한다. SecurityContextHolder를 세션 저장소로 쓰거나 전역/상속 ThreadLocal로 변경하지 않는다. 필터·Provider·저장소/처리기 호출에는 소유 컨텍스트의 TCCL을 적용하고 원래 로더를 복원한다.

## Paper 연결과 메서드 인가

```yaml
spring:
  paper:
    security:
      enabled: true
      pending-timeout-seconds: 120
      operation-timeout-seconds: 10
      shutdown-timeout-seconds: 5
      connection:
        enabled: true
        admission-timeout-seconds: 10
        refresh-period-ticks: 20
        snapshot-ttl-seconds: 5
```

기본 Paper 어댑터는 AsyncPlayerPreLoginEvent에서 시작하고 PlayerJoin/Quit와 연결 close를 추적한다. 이전 호스트 거절을 덮어쓰지 않는다. UUID와 실제 transport 주소를 함께 연결하고, 월드 입장 이후에는 Player 객체 identity로 추적한다.

기본 입장 처리기는 AUTHENTICATED만 허용한다. PENDING의 월드 입장을 소비자 처리기로 허용한다면 이동·아이템·전투 등 native gameplay 제한도 소비자가 구현해야 한다. 필터에서 next를 호출하지 않는 것만으로 Minecraft 동작이 취소되지는 않는다. 기본 어댑터는 활성화 당시 이미 접속한 플레이어에게 재접속을 요구한다. 기존 접속 복원이나 configuration-stage 대기 등 다른 흐름은 어댑터를 끄고 core API를 호출하는 자체 어댑터로 연결한다.

접속 모드의 기본 PaperSessionAuthenticationResolver는 검증한 세션 snapshot만 반환한다. PENDING·만료·무효화·너무 오래된 snapshot은 인증으로 노출하지 않는다. async 이벤트에서도 Player의 변경 가능한 권한을 읽지 않는다. 저장소 재조회가 실패하거나 TTL을 넘으면 접근을 거절하고 기본 어댑터는 Paper 스레드에서 접속 철회를 처리한다. 원격 무효화의 즉시 전파를 자동 보장하지 않으며, 정상 재조회 주기와 snapshot TTL의 지연 범위가 있다.

기본 resolver는 core의 current(snapshot)도 함께 확인한다. 소비자 관찰 이벤트가 실패하거나 지연되어도 로컬 철회/새 revision을 이전 권한으로 우회하지 않는다. 이 검사는 이미 실행 중인 업무를 소급 취소하지 않으며, 비동기 업무의 최종 반영 시 세션·revision 재검증은 업무의 책임이다.

접속 모드를 끄면 기본 PaperPlatformAuthenticationResolver가 동기 Bukkit 권한/플랫폼 신원을 사용한다. OP의 ROLE_OPERATOR와 콘솔의 ROLE_CONSOLE은 이 기본 모드의 authority다. 이 신원은 소비자의 별도 로그인/계정 연동을 완료했다는 뜻이 아니다. 세션 모드는 Provider가 만든 principal/권한을 그대로 사용하며 Bukkit 권한을 자동 병합하지 않는다. 소비자 PaperAuthenticationResolver로 원하는 권한 소스를 조합할 수 있다.

PaperSecurityInterceptor는 호출 시작에 빈 SecurityContext를 설정하고 resolver를 호출하며 finally에서 이전 문맥을 복원한다. resolver에서 기존 호출자의 권한을 실수로 상속하지 않는다. PaperAccessDeniedHandler는 명령에 boolean, 탭 완성에 List 등 해당 입력의 반환 계약을 지킨다. 이벤트의 기본 거절은 예외 전파이며 native event 취소를 대신하지 않는다.

소비자가 비동기 Spring event multicaster를 사용해도 기본 Paper snapshot 갱신/차단은 supportsAsyncExecution=false인 우선 listener로 즉시 처리한다. 소비자 관찰 이벤트의 비동기 처리와 권한 반영의 시점을 섞지 않는다.

## Velocity와 외부 OAuth 연결

Velocity 소비자는 security-core만 의존하고 자신의 이벤트 어댑터에서 동일한 서비스를 호출할 수 있다. Core의 자동 테스트는 Bukkit/Velocity API가 없는 classpath에서 소비자 Manager/Provider·저장소·대기 처리기를 주입해 검증한다. 공식 Velocity bootstrap이나 Velocity runtime을 이번 변경에 추가하지 않았다.

- 기존 프록시의 온라인/오프라인 프로토콜 선택과 Mojang 검증 결과를 소비자 어댑터가 올바른 단계에서 확인한다. PreLogin에서 요청한 online mode만으로 검증 완료로 취급하지 않는다.
- 실제 입장 판정 단계의 비동기 결과는 Velocity EventTask로 연결하고, disconnect 시 동일 connectionId를 무효화한다.
- Paper backend는 프록시의 UUID/이름만 믿는 대신 올바른 forwarding 신뢰와 소비자의 인증 증거 전달 계약을 사용한다.
- OAuth 웹 왕복·Discord client 등록/secret·HTTP endpoint·길드/역할 조건은 소비자 구현이다. 프레임워크는 공급자를 하드코딩하거나 웹 서버를 자동 실행하지 않는다.
- 최초 계정 연동 확인과 매 접속 OAuth 재인증은 서로 다른 소비자 정책이며 필터·챌린지 구현으로 선택한다.

## 종료와 Spring 내부 구현 근거

Paper 기본 어댑터는 사용자 stop 단계에서 보안을 닫아 plugin worker/DB 정리보다 앞서 접속을 철회한다. Core만 쓰는 Spring 컨텍스트도 자체 ContextClosedEvent를 동기·우선 처리하고 SmartLifecycle의 높은 phase에서 정리한다. 자식 컨텍스트의 close가 부모의 보안을 닫지 않는다. 종료는 제한된 시간만 기다리고 외부 정리 실패를 성공으로 숨기지 않는다.

실제로 확인한 Spring 7.0.10 / Security 7.1.2 구현:

- [AbstractSecurityBuilder](https://github.com/spring-projects/spring-security/blob/7.1.2/config/src/main/java/org/springframework/security/config/annotation/AbstractSecurityBuilder.java): 한 번만 build하는 공개 기반 클래스 재사용.
- [FilterChainProxy](https://github.com/spring-projects/spring-security/blob/7.1.2/web/src/main/java/org/springframework/security/web/FilterChainProxy.java): 첫 매칭 체인의 선택 방식 참고. Servlet 타입을 게임 연결로 위장하지 않음.
- [ProviderManager](https://github.com/spring-projects/spring-security/blob/7.1.2/core/src/main/java/org/springframework/security/authentication/ProviderManager.java), [AuthenticationFilter](https://github.com/spring-projects/spring-security/blob/7.1.2/web/src/main/java/org/springframework/security/web/authentication/AuthenticationFilter.java): 증거 검증과 저장·후속 처리의 책임 분리. 인증 엔진은 Spring 구현을 사용.
- [AbstractApplicationContext](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java), [ExecutorConfigurationSupport](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/scheduling/concurrent/ExecutorConfigurationSupport.java): close event가 lifecycle 정지보다 먼저이며 executor가 이 event에서 조기 종료함을 확인. phase만으로 해결되지 않던 실패를 회귀 테스트로 고정.
- [DefaultLifecycleProcessor](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/support/DefaultLifecycleProcessor.java), [SimpleApplicationEventMulticaster](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/event/SimpleApplicationEventMulticaster.java): 역 phase 정지와 supportsAsyncExecution 계약을 사용. 소비자 scheduler 설정을 바꾸지 않음.

## 타임아웃 스케줄러 선택

접속 보안의 타임아웃은 전용 `paperSecurityScheduler`에서 실행한다. 기본 빈은 일반 주입 후보에서
제외되므로 `@Scheduled` 유지보수 작업이나 메인 스레드 예약에 선택되지 않는다. 소비자의 무지정
`TaskScheduler`가 DB 작업으로 막혀 있어도 인증 대기 만료는 계속 처리한다.

교체하려면 `@Bean(name = PaperConnectionSecurityConfiguration.SCHEDULER_BEAN_NAME, defaultCandidate = false)`로
표준 TaskScheduler를 제공한다. 외부에서 소유하는 backend는 `destroyMethod = ""`로 소유권을 유지한다.
일반 스케줄러 빈이 있다는 이유만으로 보안 타임아웃에 재사용하던 이전 동작은 이 명시적 계약으로 변경했다.
