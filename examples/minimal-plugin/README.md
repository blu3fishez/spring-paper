# 진입 구성·Module SPI 예제

진입 구성과 입력/업무 클래스로 bootstrap과 설정 사용법을 보여준다. 최소 업무 흐름은 [business-plugin](../business-plugin/README.md)을 먼저 본다. [공통 빌드/실행 방법](../README.md#빌드와-실행)을 따른다.

- MinimalPlugin: SpringPaperPluginApplication으로 plugin.yml을 생성한다.
- MinimalConfiguration: 실제 Core 구성을 import하고 자기 패키지만 스캔한다.
- MinimalInitializer: Spring 표준 initializer가 @Value 입력을 준비한다.
- GreetingService: Logger·설정 주입과 Paper 전용 시작/종료를 보여준다.
- GreetingListener/Command: 수동 registerEvents/setExecutor 없이 한 서비스를 재사용한다.
- optional 패키지: Module SPI가 활성화 속성에 따라 별도 Configuration을 연결한다.

콘솔의 `paper-hello`, `paper-hi`, `paper-welcome`은 모두
`Hello from Spring Paper module=enabled, CONSOLE!`을 출력한다. 시작/종료 마커는
MINIMAL_READY/MINIMAL_STOPPED다. 플레이어 접속도 같은 greeting을 호출한다.

외부 `run/plugins/SpringPaperMinimal/application.yml`에 다음을 두면
module=disabled와 demo profile의 greeting을 확인할 수 있다.

```yaml
spring:
  profiles:
    active: demo
example:
  module:
    enabled: false
```

기본 application.yml, 선택 profile, 외부 파일, 시스템 속성/환경 변수는 같은 Spring Environment로 해석된다.
IDE는 실제 @Import/@Bean/스캔 정의를 분석한다. 임의의 ServiceLoader 제공자나 initializer 실행 결과까지
정적으로 추론하는 것은 보장하지 않는다.

활성화 속성의 누락 기본값은 false이고, 예제 YAML에는 true가 명시돼 있다.
직접 import/scan도 Configuration의 표준 Condition으로 보호한다. 독립 등록 경로·필수 의존성 실패·
빌드 시 명령 메타데이터와 runtime 어댑터의 차이는 [선택 기능 계약](../../docs/optional-features.md)을 따른다.
