# Ordinary Paper application

Java 25, Paper 26.3 build 244, Spring Boot 4.1.2의 비웹 애플리케이션 예제다.
`GameServerApplication`의 패키지 아래 `@Service`와 `@ConfigurationProperties`를 자동 발견한다.
기존 `minimal-plugin` 예제는 native carrier와 명시 scan을 사용하는 호환 예제로 유지한다.

저장소 루트에서 다음 명령은 host를 기동하지 않는다.

```shell
./gradlew -p examples/boot-paper-plugin help tasks
./gradlew -p examples/boot-paper-plugin assemble
./gradlew -p examples/boot-paper-plugin preparePaperServer
```

`help/tasks`는 YAML·compile target·실행 target·secret을 요구하지 않는다. `assemble`은
`paper.apiCoordinate`와 descriptor의 `paper.apiVersion`만 사용한다. 개발 실행 버전과 build를 제거해도
조립할 수 있다. 결과는 `build/libs/spring-paper-boot-example-0.1.0-SNAPSHOT.jar`인 flat plugin JAR다.
Boot `bootJar`를 사용하지 않는다. `runServer`를 사용하려면 `development.minecraftVersion`과 `build`를
명시해야 하며 latest를 추론하지 않는다. 실제 실행과 EULA 수락은 이 예제의 검증 범위에 포함하지 않았다.
`acceptEula=false`를 유지하므로 생성되는 `eula.txt`도 false다.

새 애플리케이션의 런타임과 준비 작업은 자기 JAR의 `application.yml` 및
`run/plugins/SpringBootPaperExample/application.yml`을 Boot ConfigData 순서로 읽는다.
native 실행 디렉터리의 `application.yml`을 암묵적으로 읽지 않는다. profile은
`SPRING_PROFILES_ACTIVE` 또는 `-Dspring.profiles.active=...`로 지정하고 다른 위치는 명시
`spring.config.location`/`spring.config.import`로 지정한다. 외부 base는 packaged profile보다 우선한다.
사용자 정의 system property는 `tasks.named('runServer') { systemProperty('app.key', 'value') }`처럼
native JavaExec에 명시한다. 같은 property와 환경을 forked 준비 helper에도 전달한다.
명시 상대 `file:` location/import는 native run 디렉터리가 기준이며 helper도 같은 cwd를 쓴다.

새/빈 `development.directory`만 자동 소유한다. 준비 작업은 전체 native 파일과
`.spring-paper/owned.properties`의 hash를 기록한다. 반복 준비는 동일한 파일을 만들며, 수동 변경은
충돌로 중단한다. 미소유 디렉터리·외부 CLI 경로에는 `build/spring-paper/native-plan.txt`와 proposal을 만들고
원본은 변경하지 않는다. 해당 상태에서는 run도 중단한다. 범용 apply나 자동 삭제는 제공하지 않는다.
계획 옆 `native-proposal.json`에서 대상 경로·hash·typed 제안 값과 secret source/environment 정책을
검토할 수 있다. 민감 키는 `<redacted>`로 표시하며 secret-file 경로는 공개한다.
plan-only에서는 secret source 파일을 열지 않는다.
`--config`, `--spigot-settings`, `--paper-settings-directory`, `--online-mode`의 실효값을 반영한다.
standalone 준비에서는 `preparePaperServer.nativeArguments`로 같은 인수를 지정할 수 있다.

Paper가 성공한 run 중 설정을 다시 포맷하거나 기본값을 추가하면, 처음 생성한 값이 모두 유지됐을 때만
종료 뒤 hash를 갱신한다. 실패 실행이나 값의 변경/삭제는 갱신하지 않는다. 실행 중 외부 편집과 native가
추가한 기본값을 완전히 구별하는 기능은 아니므로 도구 소유 파일을 실행 중 편집하지 않는다.

Velocity backend를 준비할 때는 외부 plugin dataDir 설정에 다음을 추가한다.

```yaml
spring:
  paper:
    velocity:
      enabled: true
      online-mode: true
      secret-file: '${VELOCITY_FORWARDING_SECRET_FILE}'
```

기존 공유 파일을 환경변수로 지정한다. 상대 secret-file은 native run 디렉터리를 기준으로 해석한다.
launcher는 파일 내용을 소유한 Paper 프로세스의 `PAPER_VELOCITY_SECRET`에만 전달한다.
생성 native 파일의 secret은 빈 값이며, raw secret을 JAR·descriptor·계획·로그에 쓰지 않는다.
기존 실효 native 환경변수와 파일이 다르면 값을 숨긴 채 충돌을 알린다. secret은 생성하거나 회전하지 않는다.
파일 설정 대신 기존 `PAPER_VELOCITY_SECRET`을 실행 환경에 직접 제공할 수도 있다.
backend의 `server.properties`는 online-mode=false, `spigot.yml`은 bungeecord=false가 된다.
proxy owner가 modern forwarding, 공유 secret, 일치하는 online-mode와 backend 주소 등록을 준비해야 한다.
원격 proxy 설정은 수정하지 않는다.
