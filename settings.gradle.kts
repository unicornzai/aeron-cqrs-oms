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
    "oms-app"
)
