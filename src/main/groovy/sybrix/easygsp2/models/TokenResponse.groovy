package sybrix.easygsp2.models

import com.fasterxml.jackson.annotation.JsonIgnore

class TokenResponse {
    String idToken
    String tokenType
    String username
    Long expiryDays
    Long profileAdId
    @JsonIgnore
    Long profileId
    Map data = [:]
}

