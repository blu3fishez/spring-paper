# 검증 기록 — 2026-11-09

Spring Paper 라이브러리의 빌드·자동 테스트·독립 Maven 소비와 격리된 실제 Paper 서버 기동/종료를 확인했다. 사용자가 EULA 수락을 명시한 뒤 ignored 로컬 설정에서만 accept-eula를 true로 사용했다. 배포 예제의 기본값은 false다.

## 수행한 검사

- `./gradlew build`: 전체 빌드와 104개 자동 테스트 성공. 초기 구현에서 clean build도 수행했다.
- `./gradlew -p examples/guide-plugin build --configuration-cache`: included build 소비 예제와 설정 캐시 저장·재사용 확인.
- `python3 scripts/verify-published-consumer.py`: 격리된 Maven 배포물만 쓰는 별도 소비 빌드, 설정 캐시 재사용, 현재 클래스 bytecode 대조 성공.
- Gradle 플러그인 validatePlugins와 작성 파일 600줄 검사 통과.
- run-paper 공식 다운로드 경로의 Paper 26.3 build 244 checksum 확인.
- 로컬 Paper에서 native event → 콘솔 command → JPA/cache/security 검증 성공. 소비자 Provider·대기 처리기·추가 증거로 인증 완료/정리도 확인했다.
- 저장 시점 만료 검사를 제거한 mutation이 회귀 테스트에 잡히는 것을 확인한 뒤 원본을 복원했다.
- 정상 stop에서 사용자 종료 훅 한 번, EntityManagerFactory/Hikari 종료, 테스트 포트 해제를 확인.

## 자동 검증

총 104개, 실패·오류·스킵 0.

| 모듈 | 테스트 수 |
|---|---:|
| spring-paper-cache | 7 |
| spring-paper-config | 4 |
| spring-paper-core | 38 |
| spring-paper-data-jpa | 3 |
| spring-paper-data-pdc | 10 |
| spring-paper-gradle-plugin | 3 |
| spring-paper-security | 12 |
| spring-paper-security-core | 27 |

실제 H2 어댑터에서 JPA 저장·재조회·revision·rollback과 기존 DataSource 재사용을 확인했다. 메인 스레드 트랜잭션 시작 거절도 검사했다. Cache는 실제 Spring proxy와 commit/rollback에 따른 변경을, Security는 실제 메서드 인가 proxy의 허용·거절·문맥 복원을 확인했다.

Paper 입력·생명주기 자동 검사는 Paper API 타입과 mock host를 사용한다. 실제 Bukkit 명령 객체의 실행기/탭 완성 연결·복구, 이벤트 등록 옵션, 종료 후 콜백 차단, 상속 override, 부분 시작 실패 정리, 워커 drain을 검증했다. PaperStop과 AutoCloseable 추론 종료 및 명시적 destroyMethod가 겹쳐도 종료 소유자는 한 번만 실행된다.

Cache는 Spring 표준 CacheManager 빈 재사용, Cacheable/CachePut/CacheEvict의 같은 키 갱신·무효화,
CachingConfigurer의 CacheResolver·KeyGenerator를 실제 Spring 컨텍스트로 검증했다.
소비자 resolver가 선택되면 Caffeine 설정 없이 기동하고 사용하지 않는 기본 제공자의 설정을 평가하지 않는다.
기본 관리자를 선택할 때는 기존 명시적 names/spec 계약을 유지한다. 기본 관리자를 eager 생성하도록 되돌린
mutation이 resolver 구성의 기동 성공 assertion으로 검출됐고 소스는 원본으로 복원했다.

소비자 도입 중 initializer의 메인 스레드 실행과 worker refresh, 독립 stop phase와 placeholder,
실패 정리 후 소비자 운영 정책, package-private factory 리스너의 동일 객체 재사용,
PriorityOrdered/Ordered/타입·factory @Order, lazy factory 거절, 반복 명령 메타데이터와 추가 권한 선언을 검증했다.

Security core는 Bukkit/Velocity가 없는 classpath에서 실제 Spring Manager/Provider, 소비자 저장소·대기/결과 처리기, 체인 순서·매칭, 거절 기본값, 부분 인증, 재접속·revision·만료 경쟁을 검사한다. 원격 삭제나 결과 처리가 멈춰도 로컬 철회와 인증 작업 timeout이 먼저 적용되며, 늦은 완료는 접속을 되살리지 않는다. 만료 시각은 가상 Clock/TaskScheduler로 제어한다. InMemory 저장소의 commit-time 만료 조건을 실제로 제거했을 때 `repositoryCasRejectsExpiredOrStaleRevision`이 실패했고 원본 복원 후 통과했다.

Paper 접속 검사는 mock host에서 실제 Spring Paper bootstrap과 native 이벤트 타입을 사용한다. 소비자 admission 정책, 기존 호스트 거절 보존, pending 입장/후속 인증, 입장 직전 철회, 이전 Player의 quit와 새 연결 분리, snapshot TTL·비동기 Player 권한 조회 방지·문맥 복원·소비자 기본값 교체를 확인한다. 관찰 이벤트가 실패해도 core 철회 검사가 오래된 권한을 차단한다.

Paper의 리소스 가시성 문제는 같은 Java 25/Spring 환경에서 재현했다. 일반 클래스 로더 대조군은 통과하고, Paper와 같은 local-only 리소스 로더에서 CommandExecutor.class 조회가 실패했다. 수정 후 컴포넌트 스캔·full Configuration·CGLIB cache proxy·호스트 타입 정체성·리소스 우선순위/중복 제거가 모두 통과한다.

## 실제 Paper 기동과 종료

실행 명령:

```sh
./gradlew -p examples/guide-plugin \
  -PspringPaper.configuration=application-local.yml runServer --console=plain
```

- Java 25.0.2, Paper 26.3 build 244, API 26.3.build.244-stable
- 바인딩: 127.0.0.1:25585, 기존 SimpleExtraction 서버와 격리
- 실제 등록한 커스텀 이벤트가 listener를 한 번 호출하고 ready 작업이 콘솔 명령을 실행
- Spring Security 콘솔 인가, 작업자 JPA 커밋/재조회, 반복 조회 cache hit 확인
- 소비자 AuthenticationProvider → PENDING challenge → 추가 증거 검증 → AUTHENTICATED → disconnect 및 저장소 부재 확인
- 콘솔 별칭 sp를 cache TTL 이후 다시 실행해 PDC·순서·재조회 검증 성공.
- 이번 실행 이후 갱신된 smoke-success.txt/smoke-stop.txt를 검사해 과거 marker를 성공으로 오인하지 않음
- AutoCloseable인 예제의 PaperStop은 한 번 실행; EntityManagerFactory → Hikari 순서로 정리
- 정상 stop 후 테스트 포트가 더 이상 listening하지 않음

서버 로그에서 확인한 결과:

```text
13:11:46 SPRING_PAPER_CUSTOM_START
13:11:49 SPRING_PAPER_SMOKE_OK
13:12:55 SPRING_PAPER_SMOKE_OK  (sp 별칭)
13:12:55 SPRING_PAPER_CUSTOM_STOP
13:12:55 Closing JPA EntityManagerFactory for persistence unit 'spring-paper'
13:12:55 SpringPaper - Shutdown completed.
```

smoke-success.txt:

```text
paper=26.3
console-authorized
connection-security=custom-pending-authenticated-closed
pdc=entity+item+legacy+clone+remove
jpa=committed
cache=hit
event=1
event-order=annotation+composition
```

네이티브 로그 원본은 로컬 examples/guide-plugin/run/logs/latest.log에 있고, 주요 로그와 판정은 [JSON 요약](verification-summary.json)에 보존했다. run 디렉터리와 로컬 EULA 설정은 Git에 포함하지 않는다.

## 막혔던 지점과 코드 주석

각 구현 옆의 한국어 주석에 실패 조건·원인·선택한 확장 지점을 기록했다. 실제 장애, 회귀 검사, 실행 승인 대기와 설계 제약을 구분했다.

| 구분 | 원인과 해결 | 주석 위치 |
|---|---|---|
| 실제 Paper 기동 실패 | Paper는 호스트 클래스를 로딩하면서 해당 리소스는 숨긴다. Spring 인터페이스 메타데이터 조회가 실패했다. 클래스 정의 로더는 유지하고 리소스 조회만 보완했다. | [PaperResourceLoader](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/bootstrap/PaperResourceLoader.java) |
| 종료 회귀 | 원본 bean definition 수정만으로는 캐시된 merged definition의 close 추론이 남아 이중 종료됐다. Spring의 MergedBeanDefinitionPostProcessor와 외부 관리 메서드 계약으로 처리했다. | [PaperBeanCatalog](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/bootstrap/PaperBeanCatalog.java) |
| 소비자 어댑터 접근 제약 | package-private 클래스의 public 메서드는 다른 패키지에서 invoke할 수 없다. 프록시에서 선택한 메서드만 makeAccessible한다. 세션을 캡처한 어댑터는 factory method metadata로 등록한다. | [PaperListenerRegistrar](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/listener/PaperListenerRegistrar.java), [PaperBeanCatalog](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/bootstrap/PaperBeanCatalog.java) |
| 소비자 순서 보존 | 기존 종료는 시작 역순과 다르고 같은 EventPriority의 등록 순서도 동작에 영향을 준다. 독립 stop phase와 Spring BeanFactory의 getOrder를 사용한다. | [PaperLifecycleManager](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/lifecycle/PaperLifecycleManager.java), [SpringPaperApplication](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/bootstrap/SpringPaperApplication.java) |
| 상속 회귀 | 모든 선언을 등록하면 부모/자식 override가 중복 실행되거나 제거한 어노테이션이 살아난다. 선언 검증과 실제 public override 선택을 나눴다. | [PaperListenerRegistrar](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/listener/PaperListenerRegistrar.java), [PaperLifecycleManager](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/lifecycle/PaperLifecycleManager.java) |
| 종료 순서 회귀 | Spring의 기본 destruction 순서만으로는 워커보다 인프라가 먼저 닫힐 수 있다. 입력 해제·사용자 flush·워커 drain 뒤 컨텍스트를 닫는다. | [SpringPaperApplication.close](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/bootstrap/SpringPaperApplication.java) |
| 보안 종료 회귀 | SmartLifecycle phase만 높여도 소비자 scheduler가 ContextClosedEvent에서 먼저 작업을 거절했다. 자기 컨텍스트의 close event를 동기·우선 처리해 cleanup을 완료한다. 소비자 scheduler 설정은 유지한다. | [PaperConnectionSecurityService](../spring-paper-security-core/src/main/java/org/blu3fishez/springpaper/security/connection/service/PaperConnectionSecurityService.java), [SecurityLifecycleTest](../spring-paper-security-core/src/test/java/org/blu3fishez/springpaper/security/connection/config/SecurityLifecycleTest.java) |
| Spring Cache 확장 차단 | 표준 CachingConfigurer의 resolver를 제공해도 기본 Caffeine names/spec 검증과 eager 생성이 먼저 실패했다. 기본 제공자는 Spring이 실제 선택할 때만 생성하고 표준 설정의 선택은 Spring에 맡긴다. | [CacheConfiguration](../spring-paper-cache/src/main/java/org/blu3fishez/springpaper/cache/CacheConfiguration.java), [SpringCacheCustomizationTest](../spring-paper-cache/src/test/java/org/blu3fishez/springpaper/cache/SpringCacheCustomizationTest.java) |
| 실제 Paper 비동기 인증 실패 | future 정리 whenComplete보다 소비자의 thenCompose가 먼저 실행돼 다음 인증이 진행 중으로 거절됐다. 공개 future를 완료하기 전에 내부 슬롯·타이머를 정리한다. | [PaperConnectionSecurityService.complete](../spring-paper-security-core/src/main/java/org/blu3fishez/springpaper/security/connection/service/PaperConnectionSecurityService.java), [ConnectionSecurityTest](../spring-paper-security-core/src/test/java/org/blu3fishez/springpaper/security/connection/service/ConnectionSecurityTest.java) |
| 외부 저장소 제약 | 원격 삭제 실패/정지는 로컬 권한 철회를 막아서는 안 된다. cleanup ACK와 로컬 invalidation을 분리하며 원격 TTL/원자적 만료 검사는 저장소 구현의 계약이다. | [PaperSecuritySessionRepository](../spring-paper-security-core/src/main/java/org/blu3fishez/springpaper/security/connection/spi/PaperSecuritySessionRepository.java), [InMemoryPaperSecuritySessionRepository](../spring-paper-security-core/src/main/java/org/blu3fishez/springpaper/security/connection/repository/InMemoryPaperSecuritySessionRepository.java) |
| 예제 검증 실패 | 캐시 TTL 뒤 정상 재조회인데 누적 DB 조회 횟수 1을 요구해 실패했다. 연속 두 번째 조회의 cache hit를 검사하도록 수정했다. | [ExampleCommand](../examples/guide-plugin/src/main/java/org/blu3fishez/springpaper/example/command/ExampleCommand.java) |
| Gradle 소비 빌드 실패 | afterEvaluate에서 toolchain을 바꾸면 languageVersion이 이미 finalized였다. 연동 플러그인 적용 전에 YAML Provider를 연결했다. | [Gradle SpringPaperPlugin.apply](../spring-paper-gradle-plugin/src/main/java/org/blu3fishez/springpaper/gradle/SpringPaperPlugin.java) |
| 패키징 제약 | Shadow 중복 제외가 서비스 병합보다 먼저 적용되면 선택 모듈 제공자가 사라진다. 서비스 파일을 transformer까지 전달한다. | [Gradle SpringPaperPlugin.shadowJar](../spring-paper-gradle-plugin/src/main/java/org/blu3fishez/springpaper/gradle/SpringPaperPlugin.java) |
| 배포 검증 제약 | 같은 SNAPSHOT의 캐시는 예전 배포물을 검증하게 할 수 있다. 최초 소비를 refresh하고 모든 자체 클래스 bytecode를 대조한다. | [verify-published-consumer.py](../scripts/verify-published-consumer.py) |
| 실행 승인 대기, 해소됨 | 서버 실행 허용과 EULA 수락을 따로 확인했다. 명시적 수락 후 로컬 설정만 true로 실행했다. | [PreparePaperServer.prepare](../spring-paper-gradle-plugin/src/main/java/org/blu3fishez/springpaper/gradle/PreparePaperServer.java) |
| 설계 제약 | 진입점을 Component로 스캔하면 JavaPlugin을 재생성한다. 기존 인스턴스 등록과 사용자 구성 등록을 나눈다. | [SpringPaperPluginApplication](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/annotation/SpringPaperPluginApplication.java) |
| 설계 제약 | 원본 target 호출은 인가/트랜잭션 advice를 우회한다. 프록시에 호출 가능한 메서드만 연결한다. 공유 스레드의 TCCL은 호출 범위에서 설정·복원한다. | [PaperListenerRegistrar](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/listener/PaperListenerRegistrar.java), [PaperInvocationService](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/service/PaperInvocationService.java) |
| 설계 제약 | 메인 스레드에서 종료 대기 중 queued main callback은 실행되지 않는다. pending future를 먼저 실패시키고 워커를 기다린다. | [PaperTasks.close](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/task/PaperTasks.java) |

살펴본 Spring 내부 구현과 적용한 공개 계약은 [설정·확장 문서](configuration.md#spring-내부-구현에서-확인한-확장-계약)에 버전을 고정한 소스 링크와 함께 기록했다.

Security의 builder·ProviderManager·FilterChainProxy·ContextClosedEvent 근거는 [보안 확장 계약](security.md#종료와-spring-내부-구현-근거)에 있다. 비동기 완료 순서도 [JDK 25 CompletableFuture.postComplete](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java)의 completion stack과 대조했다.

## 배포물 소비와 최종 JAR

build/verification-repository의 Maven 플러그인 marker와 모듈만으로 소비했다. includeBuild 없이 최초 refresh 빌드와 설정 캐시 재사용 빌드가 모두 성공했다. 현재 라이브러리 클래스 114개의 bytecode가 소비 JAR과 일치한다. ~/.m2나 외부 Maven 저장소에는 배포하지 않았다.

- 실제 서버에서 사용한 included-build 예제 JAR SHA-256: 5a5d17a763a53fe282322a6948288ea7ce993a9321171c14189168718439f268
- 크기: 40066516 bytes, ZIP 중복 entry 0
- Spring Boot / Bukkit / SLF4J 포함 클래스: 각각 0
- 프로젝트 클래스 major 69 (Java 25), JPA·Cache·Security SPI 제공자 4개 병합 보존
- 독립 Maven 소비 JAR SHA-256: 5f7267dc0f00345213c985746d69c960dd0699127c0f29c1e10cdbbb98497f15
- Paper 서버 JAR SHA-256: 9601f9a26a2a52a0f2c807cf1abf691f96ee4fb287878313b601cebc2c51eabf

## 검증 범위의 한계

- 실제 Minecraft 플레이어 접속·클라이언트 UI는 검증하지 않았다.
- 실제 Velocity 서버와 Discord OAuth 웹 왕복은 실행하지 않았다. 호스트 중립 core 계약과 소비자 합성 proof 예제를 검증했다. Paper 접속 admission 자체는 mock host/API 검증이다.
- DB 통합은 H2를 사용했다. MariaDB·Redis, 다중 플러그인의 서로 다른 Spring 버전 공존은 검증하지 않았다.
- SimpleExtraction은 별도 작업공간에서 2단계 도입 중이다. 이 보고서의 라이브러리 성공이 역할·인증 모드별 실제 컨텍스트나 검증계 배포 성공을 뜻하지 않는다. 원래 작업공간의 사용자 변경은 유지한다.
- Maven Central·Gradle Plugin Portal 공개 배포와 라이선스 선정은 진행하지 않았다.

## PDC·이벤트 순서·Lombok 추가 검증

- 실제 Spring FactoryBean과 제한된 스캔으로 엔티티/아이템 저장소를 생성했다. 두 저장소가 공존해도 제네릭 주입이 정확하고 비활성 profile은 제외된다.
- 기존 LONG·UUID STRING 읽기, absence/부분 누락/타입 오류/변환 오류의 구분, custom PersistentDataType 빈, record 검증, 읽기 무변경을 확인했다.
- 아이템 변경 반영·복사본 분리·선택 필드 삭제·다른 키/ItemMeta 보존을 실제 Paper에서도 확인했다. 작업자 접근은 holder를 읽기 전에 거절한다.
- 마지막 codec 실패 전에 live 엔티티를 쓰도록 바꾼 mutation이 assertion 실패로 검출됐다. 명시 관계를 무시하도록 바꾼 mutation도 검출됐고 두 소스 모두 바이트 단위로 복원했다.
- 이벤트 관계는 메서드·native HandlerList·priority 기준이다. 상속 이벤트의 공유 목록, 비활성 역할, 무관한 목록 사이의 가짜 순환 방지, 실제 순환과 native 우선순위 모순을 검사했다. 최상위 구성 SPI와 어노테이션을 합친 순환도 등록 전에 거절하며, 구성 관계를 비활성화한 mutation이 독립 순서 assertion으로 검출됐다.
- Lombok은 1.18.48 compileOnly/annotationProcessor다. 생성자·접근자 전환 전후 123개 클래스의 공개/패키지 메서드·필드 표면을 javap로 대조해 차이가 없었다. 검증/자원 획득 생성자는 유지했다.
- delombok 결과로 Javadoc을 만들어 생성된 생성자/접근자를 문서화하고 sources JAR에 lombok.config를 포함했다. 배포물과 예제에 Lombok 런타임 클래스는 없다.

PDC의 원인은 [PdcRecordMapper](../spring-paper-data-pdc/src/main/java/org/blu3fishez/springpaper/pdc/internal/PdcRecordMapper.java)의 staging 주석,
[아이템 어댑터](../spring-paper-data-pdc/src/main/java/org/blu3fishez/springpaper/pdc/PdcRepositoryFactory.java)의 read-only view 주석,
[FactoryBean/Registrar](../spring-paper-data-pdc/src/main/java/org/blu3fishez/springpaper/pdc/internal/PdcRepositoriesRegistrar.java)의 타입 발견 주석에 남겼다.
같은 리스너의 LOWEST/ MONITOR 복수 단계가 관계 대상으로 함께 선택되던 회귀를 실패 테스트로 재현했다. 같은 priority가 있으면 그 메서드들만 선택하고, 없으면 native 순서 모순을 검증한다.
전역 숫자 순서와 상속 이벤트 문제는 [PaperEventPlan](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/listener/PaperEventPlan.java)의 native HandlerList 주석에 기록했다.

GitHub 첫 push는 GH007(비공개 작성자 메일 보호)로 거절됐다. 공개 GitHub 계정 ID로 확인한 noreply 주소로 미게시 커밋을 고쳐 재시도했다. 이후 사용자의 명시적 요청으로 이 spring-paper 저장소의 로컬 user.email도 같은 주소로 설정했다. 전역 Git·다른 저장소·계정 설정은 변경하지 않았다.
