# 시작 시 기능 선택

별도 라이브러리 발견은 `SpringPaperModule` SPI, 앱 내부 기능 선택은 표준 `@Conditional`/`@Profile`이다.
앱 기능마다 SPI를 만들지 않는다. 실행 중 toggle/동적 중지는 제공하지 않는다.

## OptionalExampleModule의 정확한 범위

[minimal-plugin](../examples/minimal-plugin/README.md)의 provider는 `example.module.enabled`를 읽는다.
PaperConfiguration.enabled의 **속성 누락 기본값은 false**, 배포 예제 application.yml의 명시값은 true다.
Spring Environment의 외부 파일·profile·시스템 속성 우선순위를 따른다.

| 값 | SPI 로더 | Configuration | 빈 |
|---|---|---|---|
| 누락 / false | 등록 생략 | 직접 import/scan도 ExampleModuleEnabled 조건으로 제외 | banner 없음 |
| true | OptionalModuleConfiguration 등록 | 조건 통과 | exampleModuleBanner = module=enabled |

기존 SPI 로더의 검사만으로는 다른 import/scan 경로를 막을 수 없었다. 예제 제공자가 동일 속성의 표준
Condition을 Configuration에 둬 그 경로도 지킨다. GreetingService는 ObjectProvider로 banner 부재를 허용한다.
비활성 기능을 필수 생성자/@Bean 인자로 요구하면 일반 UnsatisfiedDependencyException으로 시작에 실패한다.
가짜 빈을 만들어 통과시키지 않는다.

조건의 범위는 해당 Configuration과 그 등록 경로다. 다른 무조건 @Bean·수동 singleton·별도 scan/import는
자기가 소유한 정의를 등록할 수 있다. 특히 `@EnableJpaRepositories`는 component scan과 다른 registrar다.
조건부 Configuration 안에 두어야 하며, 다른 무조건 registrar가 같은 패키지를 스캔하면 저장소가 등록된다.
이 경우를 실제 Spring Data 컨텍스트로 검증했다.
JPA 자체의 초기화도 끄려면 그 JPA import/Module 활성화도 함께 조건에 포함한다. 저장소 scan만 제외하고
JpaConfiguration을 전역 import하면 Spring의 LoadTimeWeaverAware 단계에서 EMF/풀이 준비될 수 있다.
@Lazy나 가짜 타입 정보로 표준 초기화를 숨기지 않는다. 연결 Security 서비스도 SmartLifecycle이므로
그 구성이 활성화되면 Spring이 시작한다. 기능 전체를 끄려면 소유 import/Module 조건을 끈다. Spring Paper가 전역에서 그 정의를 삭제하지 않는다.

## 앱 내부 기능

[business-plugin](../examples/business-plugin/README.md)은 SPI 없이 `InboxConfiguration`에
`@Conditional(InboxEnabled.class)`를 선언한다. 이 예제의 속성은 `example.inbox.enabled`이며 누락은 false다.
DataSource·Repository·Service·maintenance와 입력 어댑터를 한 구성에서 소유한다.
Command/Listener는 stereotype을 가지므로 직접 scan에도 조건을 붙였다. 나머지 업무 클래스는 plain @Bean이다.
임의의 루트 전체 scan 대신 소유 Configuration의 import를 권장한다.

명령 메타데이터 생성은 **빌드 시점**의 바이트코드를 읽는다. `paper-inbox`는 기능이 꺼져도 plugin.yml에 남는다.
실행기·탭 완성과 리스너의 연결은 **시작 시점**의 활성 빈만 대상으로 한다. 비활성 기능은 실행기나 리스너를
등록하지 않는다. 정적 command label이 없어진다고 약속하지 않는다. 필수 의존성을 모두 조건부 구성에 두거나
선택 소비자에서 ObjectProvider를 사용한다.

refresh 뒤 Environment 값을 바꿔도 빈·예약·명령·리스너가 제거되거나 다시 생성되지 않는다.
설정 변경은 다음 컨텍스트 시작 때 평가한다. 사용자에게 런타임 on/off UI를 제공하려면 별도의 업무 정책이 필요하다.
이번 작업에는 범용 toggle 프레임워크나 동적 중지를 추가하지 않았다.

[Conditional](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/context/annotation/Conditional.html)의
선언 위치별 범위를 그대로 따른다. OptionalModuleTest와 BusinessExampleTest는 누락/false/true, 직접 import/scan,
필수 의존성 실패와 refresh 후 값 변경을 검증하고 ConditionalRepositoriesTest는 독립 저장소 등록의 범위를 검증한다.
