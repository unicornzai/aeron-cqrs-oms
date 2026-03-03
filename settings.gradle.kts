rootProject.name = "oms-poc"

include(
    "oms-sbe",
    "oms-common",
    "oms-sequencer",
    "oms-ingress",
    "oms-order-aggregate",
    "oms-event-handlers",
    "oms-read-model:oms-read-model-database",
    "oms-read-model:oms-read-model-viewserver",
    "oms-api",
    "oms-app",
    "oms-fix-integration:fix-common",
    "oms-fix-integration:fix-acceptor",
    "oms-fix-integration:fix-aggregate-agent",
    "oms-fix-integration:fix-client"
)
