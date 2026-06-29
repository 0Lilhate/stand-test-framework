package ru.alfa.stand.test.core.execution;

import java.util.ArrayList;
import java.util.List;
import ru.alfa.stand.test.core.event.ReportingEvent;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.StepEvent;

/**
 * Records every reporting event in publication order so tests can assert the lifecycle sequence.
 */
final class RecordingReportingEventPublisher implements ReportingEventPublisher {

    private final List<ReportingEvent> events = new ArrayList<>();

    @Override
    public void publish(ScenarioEvent event) {
        events.add(event);
    }

    @Override
    public void publish(StepEvent event) {
        events.add(event);
    }

    List<ReportingEvent> events() {
        return List.copyOf(events);
    }
}
