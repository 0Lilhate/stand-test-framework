# stand-test-http

Shared HTTP transport for stand-test adapters. This module depends on `stand-test-core` and has no
`StepExecutor`. `stand-test-rest` uses it now; `stand-test-eq` will use it for the showcases backend and
visibility probes.

`HttpCaller` accepts a fully resolved `RestRequest` and returns a `RestResponse`. The default
`WebClientHttpCaller` uses Spring WebClient with the JDK HTTP connector. Its no-arg constructor keeps
the REST defaults: 10 seconds to connect and 30 seconds for a response. Adapters needing other bounds
use `WebClientHttpCaller.create(connectTimeout, responseTimeout)` with positive `Duration` values.

`EnvironmentBaseUrlResolver` and `EnvironmentAuthHeaderResolver` resolve registry references when a
request runs. `CorrelationHeader.inject(...)` adds the SDK-owned correlation ID after the adapter has
decided whether injection is enabled. The transport does not interpret step parameters or assertions.

`RecordingHttpServer` and `FakeHttpCaller` live in Gradle test fixtures for adapter tests and are not
part of the published main artifact.
