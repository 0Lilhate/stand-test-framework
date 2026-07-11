# db/

Datasource whitelist entries (collection key `datasources`, schema [`../schema/datasource.schema.json`](../schema/datasource.schema.json)) and sanctioned single-value select probes with named :params (collection key `dbProbes`, schema [`../schema/db-probe.schema.json`](../schema/db-probe.schema.json)). Probes reference datasources by id; scope by :testRunId. See [`example-datasources.yml`](example-datasources.yml) / [`example-db-probes.yml`](example-db-probes.yml).
