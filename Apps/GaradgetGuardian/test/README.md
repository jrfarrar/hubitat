# Garadget test rig (2026-10-31)

Runs the REAL driver / app source on a virtual clock with Hubitat stubbed. Groovy 4.0.33 jars come from
the npm package `npm-groovy-lint` (lib/java/groovy/lib) because Maven Central is blocked in the sandboxes.

    CP=groovy-4.0.33.jar:groovy-json-4.0.33.jar:groovy-dateutil-4.0.33.jar
    java -cp $CP org.codehaus.groovy.tools.FileSystemCompiler -d classes HubSim.groovy
    java -cp $CP:classes groovy.ui.GroovyMain Runner.groovy garadgetmqtt.groovy scenarios          # 36 checks
    java -cp $CP:classes groovy.ui.GroovyMain Runner.groovy garadgetmqtt.groovy replay hist.csv 1955 # TSDB replay
    java -cp $CP groovy.ui.GroovyMain AppRunner.groovy GaradgetGuardianChild.groovy                 # 32 checks

hist.csv = `_scratch\export_hist.py` (TSDB, doors 1953/1954/1955, sensor/door/time/bright/signal/mtt/srt).
Mutation-checked: v1.4.5, "trust the closed string", frozen check off, clock seeded from now, no door-moving
guard, no outlet restore, open door not escalated - each makes the suite fail.
