package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.io.Serializable;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.tasks.JavaExec;

final class RunHostFinalization implements Action<Task>, Serializable {
    private final NativeLaunchSession session;
    RunHostFinalization(NativeLaunchSession session) { this.session = session; }
    @Override public void execute(Task task) {
        var execution = ((JavaExec) task).getExecutionResult().getOrNull();
        if (session.snapshot == null || execution == null || execution.getExitValue() != 0) return;
        try { OwnedRunDirectory.rebaseline(session.snapshot); }
        catch (IOException error) { throw new GradleException("Could not record native default additions after execution", error); }
        finally { session.snapshot = null; }
    }
}
