# Velocity 최소 예제

Java 25, Velocity API 4.2.1-SNAPSHOT, Spring Framework 7.0.10을 사용한다. 기존 Paper 예제와
별개인 순수 Velocity 소비 프로젝트이며 Paper core와 Paper Gradle 플러그인을 포함하지 않는다.

```sh
./gradlew -p examples/velocity-plugin build
```

산출물은 `build/libs/spring-paper-velocity-example-0.1.0-SNAPSHOT-all.jar`다.
Velocity의 native annotation processor가 `velocity-plugin.json`을 만들고 Shadow가 Spring을 포함한다.
Velocity와 Guice, SLF4J는 호스트가 제공한다.

호스트가 생성한 `VelocityExamplePlugin`의 Guice 생성자는 그대로 두고 initialize/shutdown 이벤트의
반환값을 `SpringVelocityApplication`에 위임한다. 실제 호스트 빈은 `VelocityHostConfiguration`으로
주입한다. 기능별 클래스의 `@VelocityEventListener`·`@VelocityCommand`가 활성 빈을 등록하며
새 기능을 추가할 때 중앙 listener/command 목록을 편집하지 않는다.

`spring-velocity` 또는 `sv`는 현재 인원을 출력한다. `PresenceService`의 일반 `@Scheduled` 작업은
소비자가 선언한 표준 `ThreadPoolTaskScheduler`를 사용하고 컨텍스트와 함께 정지한다.
인증·admission 정책은 포함하지 않는다. 해당 정책이 있는 소비자는 native 진입점에서 `isReady()`로
시작 실패와 종료 요청을 차단한다. [Velocity 계약](../../docs/velocity.md)을 참고한다.

이 예제는 빌드와 최종 JAR 내용을 확인했다. 실제 프록시 배포·기동·플레이어 접속은 실행하지 않았다.
