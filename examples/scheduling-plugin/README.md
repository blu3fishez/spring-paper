# 스케줄링·실행 예제

[공통 빌드/실행 방법](../README.md#빌드와-실행)을 따른다. Done 이후 1초 이상 기다리고
콘솔에서 `paper-scheduling`을 실행한다.

- SchedulingProbeService: 무지정 @Scheduled는 background, scheduler를 지정한 메서드는 Paper main이다.
- 같은 표준 TaskScheduler의 일회성 지연·Future 취소도 사용한다.
- tick 기반 두 반복은 Bukkit API의 단위를 유지한다. BukkitRunnable은 세 번째 실행에서 스스로 취소한다.
- AsyncProbeService: @EnableAsync와 @Async("paperTasks")로 기존 가상 스레드를 재사용한다.
  다른 빈의 호출을 프록시가 가로채므로 이 클래스는 final이 아니다.
- UtilityVerificationService: PaperKeys·PluginInfo·기존 Bukkit 설정 저장과 공유 청크 lease를 확인한다.
  설정 저장은 main에서 한 번 준비한 뒤 worker에서 완료하고, 반복 명령은 같은 Future를 공유한다.

성공하면 SCHEDULING_OK(main/worker/ticks는 실행 횟수), UTILITIES_OK, ASYNC_OK virtual=true를 출력한다.
각 횟수의 고정값을 요구하지 않고 실행 위치·자체 취소 3회·취소된 일회성 0회를 검사한다.
종료하면 SCHEDULING_STOPPED가 출력된다.

probe의 counter·Future·stop은 테스트할 여러 API를 드러내기 위한 장치다.
일반 유지보수 메서드 하나는 이 장치 없이 @Scheduled와 업무 본문만 작성한다.
[생산 코드 전환 예제](../../docs/scheduling.md)를 함께 참고한다.
