package examples.bootpaper;

import org.springframework.stereotype.Service;

@Service
public class GreetingService {
    private final GameSettings settings;
    public GreetingService(GameSettings settings) { this.settings = settings; }
    public String greeting() { return settings.greeting(); }
}
