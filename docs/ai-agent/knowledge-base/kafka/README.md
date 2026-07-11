# kafka/

One entry per topic contract: direction (produced = tests kafka.expect), HEADER-only correlation, equals-only assertions, mandatory bounded timeout. Real topic names live per environment (`../environments/`). Schema: [`../schema/kafka-topic.schema.json`](../schema/kafka-topic.schema.json); collection key `kafkaTopics`. See [`example-topics.yml`](example-topics.yml).
