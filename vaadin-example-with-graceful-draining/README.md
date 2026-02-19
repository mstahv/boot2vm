# Vaadin app example with graceful upgrades

Vaadin apps are stateful by design. This example showcases the graceful blue green deployment feature in the boot2vm project. The special in this setup is that all sessions are marked as bound to current node once started. In some stateless or semi-statelss apps you can in some cases mark only on certain states, but otherwise principles are the same. Also with Vaadin apps e.g. if it also provides e.g. some static file web pages.

As this app is Spring Boot based, we'll use its "actuactor" functionality to provide the "private API" apps need to provide for the Deploy script, and configuring that to a custom port.


