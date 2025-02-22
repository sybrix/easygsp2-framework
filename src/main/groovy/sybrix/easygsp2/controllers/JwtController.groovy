package sybrix.easygsp2.controllers

import sybrix.easygsp2.data.JsonSerializerImpl
import sybrix.easygsp2.exceptions.BadRequestException
import sybrix.easygsp2.http.HttpResponse
import sybrix.easygsp2.http.HttpStatus
import sybrix.easygsp2.models.CreateClientRequest
import sybrix.easygsp2.models.NewScopeRequest
import sybrix.easygsp2.models.TokenResponse
import sybrix.easygsp2.security.AuthenticationService
import sybrix.easygsp2.security.ClaimType
import sybrix.easygsp2.security.Claims
import sybrix.easygsp2.security.JwtUtil
import sybrix.easygsp2.security.ProfileInfo
import sybrix.easygsp2.util.Base64
import sybrix.easygsp2.util.PropertiesFile
import sybrix.easygsp2.util.Validator

import javax.servlet.ServletContext
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.time.LocalDateTime
import java.time.ZoneId

class JwtController {
        PropertiesFile propertiesFile
        AuthenticationService authenticationService

        public TokenResponse generateToken(HttpServletRequest request, HttpServletResponse response) {
                String authorizationHeader = request.getHeader("Authorization");

                if (authorizationHeader == null) {
                        throw new BadRequestException("Missing \"Authorization\" header")
                }
                String decodedAuthorizationHeader = new String(Base64.decode(authorizationHeader.substring(6)))

                def parts = decodedAuthorizationHeader.split(":")
                String emailAddress = parts[0]
                String password = parts[1]

                PropertiesFile propertiesFile = (PropertiesFile) request.getServletContext().getAttribute(PropertiesFile.KEY)
                Long expirySeconds = propertiesFile.getLong("jwt.expires_in_seconds", (60 * 60 * 24))

                authenticatePassword(emailAddress, password, expirySeconds, request.getServletContext())
        }

        public TokenResponse generateTokenFromPhone(HttpServletRequest request, HttpServletResponse response) {
                String authorizationHeader = request.getHeader("Authorization");

                if (authorizationHeader == null) {
                        throw new BadRequestException("Missing \"Authorization\" header")
                }
                String decodedAuthorizationHeader = new String(Base64.decode(authorizationHeader.substring(6)))

                //def parts = decodedAuthorizationHeader.split(":")
                JsonSerializerImpl jsonSerializer = new JsonSerializerImpl()
                MobilePhone mobilePhone = jsonSerializer.parse(decodedAuthorizationHeader.substring(0,decodedAuthorizationHeader.length()-1), MobilePhone.class)
                String code = mobilePhone.smsCode

                //PropertiesFile propertiesFile = (PropertiesFile) request.getServletContext().getAttribute(PropertiesFile.KEY)
                Long expirySeconds = propertiesFile.getLong("jwt.expires_in_seconds", (60 * 60 * 24))

                authenticateCode(mobilePhone, code, expirySeconds, request.getServletContext())
        }

        public TokenResponse authenticateCode(MobilePhone mobilePhone, String code, Long expirySeconds, ServletContext context) {
                ProfileInfo profileInfo = authenticationService.validatePhoneCredentials(mobilePhone, code)

                authenticate(profileInfo.profileId, profileInfo.email, expirySeconds, context)
        }

        public TokenResponse authenticatePassword(String emailAddress, String password, Long expirySeconds, ServletContext context) {
                ProfileInfo profileInfo = authenticationService.validateCredentials(emailAddress, password)

                authenticate(profileInfo.profileId, profileInfo.email, expirySeconds, context)
        }

        public TokenResponse authenticate(Long profileId, String emailAddress, Long expirySeconds, ServletContext context) {
                authenticate(profileId, emailAddress, expirySeconds, context, authenticationService)
        }

        public static TokenResponse authenticate(Long profileId, String emailAddress, Long expirySeconds, ServletContext context, AuthenticationService authenticationService) {

                LocalDateTime currentTime = LocalDateTime.now()
                LocalDateTime expireTime = currentTime.plusSeconds(expirySeconds);
                Date expiryDate = Date.from(expireTime.atZone(ZoneId.systemDefault()).toInstant());

                Claims claims = new Claims()
                claims.add(ClaimType.SUBJECT, emailAddress)
                claims.add(ClaimType.EXPIRATION_TIMESTAMP, String.valueOf(expiryDate.time))
                claims.add(ClaimType.PROFILE_ID, profileId.toString())

                def scopes = []
                authenticationService.getRoles(profileId.toString()).each {
                        scopes.add(it)
                }
                PropertiesFile propertiesFile = (PropertiesFile) context.getAttribute(PropertiesFile.KEY)
                def adminEmails = propertiesFile.getString("admin.emails","").split(",")
                
                adminEmails.each {
                        if (it.trim().equalsIgnoreCase(emailAddress)) {
                                scopes.add("admin")
                        }
                }

                TokenResponse tokenResponse = new TokenResponse()
                tokenResponse.idToken = JwtUtil.instance.create(emailAddress, claims, scopes)
                tokenResponse.username = emailAddress
                tokenResponse.expiryDays = expirySeconds / (24 * 60 * 60)
                tokenResponse.profileId = profileId
                
                tokenResponse = authenticationService.tokenResponsePostProcess(tokenResponse)
                return tokenResponse
        }

        def createClient(CreateClientRequest createClientRequest, HttpServletRequest request) {
                //AuthenticationService authenticationUtil = request.getServletContext().getAttribute("authenticationUtil")
                def client = authenticationService.addClient(createClientRequest.name)

                new HttpResponse(HttpStatus.CREATED, client)
        }

        def addScope(NewScopeRequest clientRequest, HttpServletRequest request) {
                //AuthenticationService authenticationUtil = request.getServletContext().getAttribute("authenticationUtil")

                def client = authenticationService.insertScope(clientRequest.clientId, clientRequest.scope)
                new HttpResponse(HttpStatus.CREATED, client)
        }
}


class MobilePhone {
        String phone
        String countryCode
        String country
        String smsCode
        String email
}
