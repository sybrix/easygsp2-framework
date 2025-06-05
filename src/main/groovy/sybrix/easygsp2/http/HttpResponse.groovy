package sybrix.easygsp2.http

import com.fasterxml.jackson.annotation.JsonIgnore

class HttpResponse {
        Integer code
        @JsonIgnore
        Integer httpStatusCode
        Object entity

        HttpResponse(HttpStatus httpStatus, Object obj) {
                httpStatusCode = httpStatus.val()
                this.entity = obj
        }

        HttpResponse(Integer statusCode, Integer errorCode, Object obj) {
                this.code = errorCode
                this.httpStatusCode = statusCode
                this.entity = obj
        }

        HttpResponse(Integer statusCode, Object obj) {
                this.httpStatusCode = statusCode
                this.entity = obj
        }

        HttpResponse(Integer statusCode) {
                this.httpStatusCode = statusCode
        }

        HttpResponse(HttpStatus httpStatus) {
                this.httpStatusCode = httpStatus.val()
        }

        public static HttpResponse OK() {
                new HttpResponse(HttpStatus.OK)
        }
        
        public static HttpResponse NOT_MODIFIED() {
                new HttpResponse(HttpStatus.NOT_MODIFIED)
        }

        public static HttpResponse NOT_FOUND() {
                new HttpResponse(HttpStatus.NOT_FOUND)
        }

        public static HttpResponse BAD_REQUEST() {
                new HttpResponse(HttpStatus.BAD_REQUEST)
        }

        public static HttpResponse SERVER_ERROR() {
                new HttpResponse(HttpStatus.INTERNAL_SERVER_ERROR)
        }
}
