rootProject.name = "oms-poc"

include(
    "oms-sbe",
    "oms-common",
    "oms-sequencer",
    "oms-ingress",
    "oms-event-handlers",
    "oms-read-model:oms-read-model-database",
    "oms-read-model:oms-read-model-viewserver",
    "oms-api",
    "oms-app",
    "oms-media-driver",
    "oms-fix-client-gateway:fix-common",
    "oms-fix-client-gateway:fix-acceptor",
    "oms-fix-client-gateway:fix-aggregate-agent",
    "oms-fix-client-gateway:fix-client",
    "oms-command-handlers:oms-aggregate-fix-order",
    "oms-command-handlers:oms-aggregate-client-order"
)
