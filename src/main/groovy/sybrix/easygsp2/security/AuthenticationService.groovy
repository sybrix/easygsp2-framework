package sybrix.easygsp2.security

import sybrix.easygsp2.exceptions.LoginFailedException
import sybrix.easygsp2.exceptions.UnauthorizedException
import sybrix.easygsp2.models.TokenResponse

import javax.servlet.http.HttpServletRequest

interface AuthenticationService {
        public Long validateCredentials(String username, String password) throws UnauthorizedException

        public Long validatePhoneCredentials(String username, String code) throws UnauthorizedException

        public List<String> getRoles(String id)

        public Client addClient(String name)

        public TokenResponse tokenResponsePostProcess(TokenResponse response)

}
