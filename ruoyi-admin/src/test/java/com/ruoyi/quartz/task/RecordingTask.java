package com.ruoyi.quartz.task;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Only records literal arguments; never opens URLs or evaluates expressions. */
public class RecordingTask extends RyTask implements TaskCalls
{
    public static final BlockingQueue<List<Object>> CALLS = new LinkedBlockingQueue<>();

    @Override
    public void ryParams(String value)
    {
        CALLS.add(List.of(value));
    }

    @Override
    public void ryNoParams()
    {
        CALLS.add(List.of());
    }

    @Override
    public void ryMultipleParams(String s, Boolean b, Long l, Double d, Integer i)
    {
        CALLS.add(List.of(s, b, l, d, i));
    }
}
