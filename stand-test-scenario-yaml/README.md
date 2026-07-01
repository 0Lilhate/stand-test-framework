# stand-test-scenario-yaml

**Group:** AI / DSL · **Gradle plugin:** `java-library`

YAML scenario engine: parses declarative scenario files into the generic `Scenario` model (a second
input to the same model as the Java DSL), then runs them through the shared core runner.

**Planned internal dependencies:** `stand-test-core` **only**. Step executors are discovered via the
core `StepExecutor` SPI at runtime, so there are **no compile-time edges** to the adapter modules
(`rest`/`kafka`/`db`/`grpc`) and the runner is not duplicated (plan §4/§5). External: SnakeYAML.

> Skeleton stage: no YAML parser / DSL implemented yet. Design: `docs/arch/stand-test-scenario-yaml-design.md`
> (Iteration 9, design-only).
