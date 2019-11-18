package sybrix.easygsp2.http;

import groovy.json.JsonSlurper
import groovy.transform.ToString
import sybrix.easygsp2.data.JsonSerializerImpl

/**
 * Created by davidl.smith on 11/4/14.
 */
//@ToString
class RestResponse {
        Boolean success
        Integer code
        Map<String, String> headers = [:]
        byte[] rawResponse
        Object jsonObject

        def getHeaderValue(String header) {
                headers.get(header)?.get(0)
        }

        def getHeaderValues(String header) {
                headers.get(header)
        }

        def parseJson(Class cls) {
                new JsonSerializerImpl().parse(new String(rawResponse), cls)
        }

        def toJson() {
                if (!jsonObject) {
                        jsonObject = new JsonSlurper().parseText(new String(rawResponse))
                }

                if (jsonObject) {
                        return jsonObject
                }
        }

        def toXML() {
                new XmlSlurper().parseText(new String(rawResponse))
        }

        Boolean getSuccess(){
                if (code >= 200 || code < 300){
                        return true
                } else {
                        return false
                }
        }

        String toJsonString() {
                if (headers.get("Content-Type")?.contains("application/json")){
                        return new String(rawResponse)
                } else {
                        ""
                }
        }

//        String toJsonString(boolean prettyPrint) {
//                new String(rawResponse)
//        }
}
