package sybrix.easygsp2.framework

import sybrix.easygsp2.EasyGsp2
import sybrix.easygsp2.anno.NoCache
import sybrix.easygsp2.anno.Secured

class ClassFactory {
        static Map<String,Object> instances = [:]

        static GroovyObject create(String cls, Object app){
                create(Class.forName(cls),app)
        }


        static GroovyObject create(Class cls, Object app){

                GroovyObject instance
                NoCache annoNoCache = (NoCache)cls.getAnnotation(NoCache.class)
                boolean cached = false

                if (annoNoCache == null) {
                        if (instances.containsKey(cls.name)){
                                instance = instances[cls.name]
                                cached = true
                        } else {
                                instance = cls.newInstance()
                                instances[cls.name] = instance
                        }
                }else {
                        instance = cls.newInstance()
                }

                if (isProperty(cls,"propertiesFile")){
                        //println("propertiesFile")
                        instance.propertiesFile =  app.propertiesFile
                }

                if (isProperty(cls,"emailService")){
                        //println("emailService")
                        instance.emailService =  app.emailService
                }

                if (isProperty(cls,"authenticationService")){
                        //println("authenticationService")
                        instance.authenticationService =  app.getAuthenticationService()
                }

                if (instance.metaClass.respondsTo(instance, 'init') && cached == false){
                        instance.init()
                }

                instance
        }

        protected static boolean isProperty(Class clazz, String propertyName) {
                def metaProperty = clazz.metaClass.getMetaProperty(propertyName)
                if (metaProperty instanceof MetaBeanProperty) {
                        return true
                } else {
                        return false
                }
        }

}
