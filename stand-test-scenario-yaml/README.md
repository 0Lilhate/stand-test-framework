# stand-test-scenario-yaml

**Group:** AI / DSL · **Gradle plugin:** `java-library`

YAML scenario engine: parses declarative scenario files into the generic `Scenario` model (a second
input to the same model as the Java DSL), then runs them through the shared core runner.

**Internal dependencies:** `stand-test-core` **only**. Step executors are discovered via the core
`StepExecutor` SPI at runtime, so there are **no compile-time edges** to the adapter modules
(`rest`/`kafka`/`db`/`grpc`) and the runner is not duplicated (plan §4/§5). External: SnakeYAML.

## Usage

`YamlScenarioParser` reads a declarative YAML scenario into the generic core `Scenario` — it only *builds*
the model (no IO, no execution); the consumer runs it through the same `StandClient` as the Java DSL:

```java
Scenario scenario = new YamlScenarioParser().parseResource("scenarios/flow.yaml"); // or parse(String)
stand.run(scenario);   // StandClient → DefaultScenarioRunner → StepExecutor SPI (adapters on the classpath)
```

The surface syntax (ergonomic `given`/`then`, `assert`/`capture` maps, `timeout` durations as `<n>ms`/`<n>s`/`<n>m` or a bare number of ms (e.g. `timeout: 30s`, `timeout: 2m`), whitelisted aliases,
`${...}` placeholders) is translated into the exact `GenericStep` parameter keys the adapters read. YAML is
loaded with SnakeYAML's `SafeConstructor`; malformed input fails closed with a located `StandTestException`.
Full design and the surface→internal mapping: `docs/arch/stand-test-scenario-yaml-design.md`.
