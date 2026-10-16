package dev.armyfps;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * One rendered frame, recorded only while a Java Flight Recording runs (otherwise free). Lets a
 * recording line up GC pauses, render-thread samples and locks with exactly the frames that stutter.
 */
@Name("armyfps.Frame")
@Label("Frame")
@Category("ArmyFPS")
@Description("One GameRenderer.render call")
@StackTrace(false)
public final class FrameEvent extends Event {
    @Label("World render")
    @Timespan(Timespan.NANOSECONDS)
    public long worldNanos;

    @Label("Players drawn")
    public int players;

    @Label("ArmyFPS on")
    public boolean enabled;
}
