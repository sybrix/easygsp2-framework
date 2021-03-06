package sybrix.easygsp2.util

import groovy.util.logging.Slf4j

@Slf4j
class GroovyBeanUtil {
        def static copyProperties(GroovyObject destination, GroovyObject source) {
                copyProperties(destination, source, [])
        }

        def static copyProperties(GroovyObject destination, GroovyObject source, List<String> excludeFromSource) {

                source.metaPropertyValues.each {
                        try {
                                if (!excludeFromSource.contains(it.name)) {
                                        destination[it.name] = source[it.name]
                                }
                        } catch (MissingPropertyException e) {
                                log.warn(e.getMessage())
                        } catch (Exception e) {
                                log.debug(e.getMessage(), e)
                        }
                }
        }
}
