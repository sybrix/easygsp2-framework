package sybrix.easygsp2;

import groovy.lang.*;
import groovy.text.SimpleTemplateEngine;
import groovy.text.Template;
import io.jsonwebtoken.ExpiredJwtException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.LocalVariableAttribute;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.FileUtils;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.runtime.GroovyCategorySupport;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeElementsScanner;
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;
import sybrix.easygsp2.anno.*;
import sybrix.easygsp2.categories.RoutingCategory;
import sybrix.easygsp2.controllers.JwtController;
import sybrix.easygsp2.data.Serializer;
import sybrix.easygsp2.data.XmlSerializer;
import sybrix.easygsp2.email.EmailService;
import sybrix.easygsp2.exceptions.*;
import sybrix.easygsp2.fileupload.FileUpload;
import sybrix.easygsp2.framework.*;
import sybrix.easygsp2.http.MediaType;
import sybrix.easygsp2.http.ControllerResponse;
import sybrix.easygsp2.http.MimeTypes;
import sybrix.easygsp2.models.ClaudeAPI;
import sybrix.easygsp2.models.ClaudeAPIParameter;
import sybrix.easygsp2.models.CreateClientRequest;
import sybrix.easygsp2.routing.MethodAndRole;
import sybrix.easygsp2.routing.Route;
import sybrix.easygsp2.routing.Routes;
import sybrix.easygsp2.routing.UrlParameter;
import sybrix.easygsp2.security.*;
import sybrix.easygsp2.templates.RequestError;
import sybrix.easygsp2.templates.TemplateInfo;
import sybrix.easygsp2.templates.TemplateWriter;
import sybrix.easygsp2.util.JsonTypeConverter;
import sybrix.easygsp2.util.PropertiesFile;
import sybrix.easygsp2.util.StringUtil;

import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import javax.servlet.*;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.Principal;
import java.util.*;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import org.slf4j.LoggerFactory;
import sybrix.easygsp2.util.StringUtil;

import static sybrix.easygsp2.util.StringUtil.combine;
import static sybrix.easygsp2.util.StringUtil.isEmpty;

/**
 * Created by dsmith on 7/19/16.
 */
public class EasyGsp2 {
        private static final org.slf4j.Logger logger = LoggerFactory.getLogger(EasyGsp2.class);

        private static final String JSON_SERIALIZER_CLASS = "sybrix.easygsp2.data.JsonSerializerImpl";
        private static final String XML_SERIALIZER_CLASS = "sybrix.easygsp2.data.XmlSerializerImpl";
        public static final String ROUTE_REQUEST_ATTR = "__route__";

        public static final MimeType APPLICATION_JSON = parseMimeType("application/json");
        public static final MimeType APPLICATION_XML = parseMimeType("application/xml");
        public static final MimeType APPLICATION_HTML_TEXT = parseMimeType("text/html");

        private static Map<String, sybrix.easygsp2.routing.Route> routes = new LinkedHashMap<String, sybrix.easygsp2.routing.Route>();
        private GroovyClassLoader groovyClassLoader;
        private PropertiesFile propertiesFile;
        private ServletContext context;

        protected Serializer jsonSerializerInstance;
        protected XmlSerializer xmlSerializerInstance;
        protected List<Pattern> ignoredUrlPatterns;

        private Set<String> classesWithApiAnnotation = new HashSet<String>();
        private Map<String, String> methods = new HashMap<String, String>();
        private boolean isServlet = false;
        private AppListener appListener;

        private Validator validator;
        private EmailService emailService;
        private AuthenticationService authenticationService;
        private Map<String, Object> dynamicProperties = new HashMap<>();

        public static EasyGsp2 instance;

        public EasyGsp2(FilterConfig config) {
                init(config.getServletContext(), config);
        }

        public EasyGsp2(ServletConfig config) {
                init(config.getServletContext(), config);
        }

        public void init(ServletContext servletContext, Object config) {
                try {
                        instance = this;
                        this.context = servletContext;
                        System.setProperty("easygsp.version", "@easygsp_version");

                        logger.info(
                                "\nEASYGSP_VERSION: " + System.getProperty("easygsp.version") +
                                        "\nJRE_HOME: " + System.getProperty("java.home") +
                                        "\nJAVA_VERSION: " + System.getProperty("java.version") +
                                        "\nGROOVY_VERSION: " + GroovySystem.getVersion() +
                                        "\n"
                        );

                        propertiesFile = new PropertiesFile("classPath:easygsp.properties");
                        servletContext.setAttribute(PropertiesFile.KEY, propertiesFile);

                        if (config instanceof ServletConfig) {
                                isServlet = true;
                        }

                        // delete tmp dir
                        File folder = new File(propertiesFile.getString("easygsp.tmp.dir", System.getProperty("java.io.tmpdir")));
                        if (folder.exists()) {
                                FileUtils.cleanDirectory(folder);
                        } else {
                                folder.mkdirs();
                        }

                        CompilerConfiguration configuration = new CompilerConfiguration();
                        configuration.setTargetDirectory(getCompiledClassesDir());
                        //configuration.setRecompileGroovySource(propertiesFile.getString("mode", "dev1").equalsIgnoreCase("dev"));

                        groovyClassLoader = new GroovyClassLoader(this.getClass().getClassLoader(), configuration);
                        //groovyClassLoader.addURL(getSourcePath(propertiesFile));

                        ignoredUrlPatterns = new ArrayList<Pattern>();

                        ThreadBag.set(new ThreadVariables(this.context, null, null, routes, null, null, groovyClassLoader));

                        if (propertiesFile.getString("authentication.service") != null) {
                                Class clsAuth = Class.forName(propertiesFile.getString("authentication.service"));
                                authenticationService = (AuthenticationService) clsAuth.newInstance();
                        } else {
                                logger.debug("No authentication.service value found");
                        }

                        if (propertiesFile.getString("email.service") != null) {
                                emailService = (EmailService) Class.forName(propertiesFile.getString("email.service")).newInstance();
                                context.setAttribute("emailService", emailService);
                        } else {
                                logger.debug("No email.service value found");
                        }

                        loadDefaultRoutes();//1
                        loadApiMethods(propertiesFile);//2
                        loadClaudAPIDocs(propertiesFile);
                        //generateEnhancedClaudeAPIDocs(propertiesFile);
                        loadUnannotatedClasses(propertiesFile, "controllers.package"); //3 order matters
                        loadUnannotatedClasses(propertiesFile, "api.controllers.package"); //3 order matters
                        loadSerializers(propertiesFile);
                        loadPropertiesIntoContext(servletContext, propertiesFile);
                        loadJwtValues(propertiesFile);

                        String appListenerClass = null;
                        if (config instanceof ServletConfig) {
                                appListenerClass = ((ServletConfig) config).getInitParameter("appListener");
                        } else if (config instanceof FilterConfig) {
                                appListenerClass = ((FilterConfig) config).getInitParameter("appListener");
                        }
                        // load DB String queries
                        ThreadBag.set(new ThreadVariables(servletContext));

                        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
                        validator = factory.getValidator();

                        if (appListenerClass != null) {
                                Class cls = Class.forName(appListenerClass, false, groovyClassLoader);
                                //appListener = (AppListener) cls.newInstance();
                                appListener = (AppListener) ClassFactory.create(cls, this);
                                appListener.onApplicationStart(servletContext);
                        }

                } catch (Throwable e) {
                        logger.error("error occurred easygsp init()", e);
                } finally {
                        ThreadBag.remove();
                }
        }

        private void loadDefaultRoutes() {
                if (propertiesFile.getBoolean("jwt.controller.enabled", false)) {
                        Routes.add("POST", propertiesFile.getString("jwt.token.url", "/api/token"), JwtController.class, "generateToken", new Class[]{HttpServletRequest.class, HttpServletResponse.class}, null, new String[]{MediaType.JSON}, new String[]{MediaType.JSON}, false);
                        Routes.add("POST", propertiesFile.getString("jwt.tokenMobile.url", "/api/tokenMobile"), JwtController.class, "generateTokenFromPhone", new Class[]{HttpServletRequest.class, HttpServletResponse.class}, null, new String[]{MediaType.JSON}, new String[]{MediaType.JSON}, false);
                        Routes.add("POST", "/client", JwtController.class, "createClient", new Class[]{CreateClientRequest.class, HttpServletRequest.class}, new String[]{}, new String[]{MediaType.JSON}, new String[]{MediaType.JSON}, true);
                }
        }

        private void loadJwtValues(PropertiesFile propertiesFile) {

                JwtUtil jwtUtil = new JwtUtil(propertiesFile.getString("jwt.alg", "HS256"));
                String key = propertiesFile.getString("jwt.key");
                if (key == null) {
                        logger.info("no jwt key found. generating key....");
                        key = jwtUtil.generateKey();
                        logger.info("generated jwt key, HS256: " + key);
                }

                jwtUtil.loadKey(key);
                JwtUtil.instance = jwtUtil;
        }

        protected AppListener getAppListener() {
                return appListener;
        }

        public String getCompiledClassesDir() {
                try {
                        File f = new File(this.getClass().getClassLoader().getResource("./../../WEB-INF/classes").toURI());
                        logger.info("compiled classed dir: " + f.getAbsolutePath());
                        return f.getAbsolutePath();
                } catch (Exception e) {
                        throw new RuntimeException("failed on getCompiledClassesDir()", e);
                }
        }

        private URL getSourcePath(PropertiesFile propertiesFile) {
                String dir = propertiesFile.getProperty("source.dir", null);
                URL url = null;
                try {
                        if (dir != null) {
                                url = new File(dir).toURI().toURL();
                        } else {
                                url = this.getClass().getClassLoader().getResource("./../../");
                        }

                        logger.info("source path: " + url.getFile());
                        return url;
                } catch (MalformedURLException e) {
                        throw new RuntimeException("Failed to determine source directory.  " + e.getMessage(), e);
                }
        }

        public boolean doRequest(final EasyGspServletRequest httpServletRequest, final HttpServletResponse httpServletResponse) throws ServletException {


                String uri = null;
                String contextPath = httpServletRequest.getServletContext().getContextPath();
                if (contextPath.length() > 1) {
                        uri = httpServletRequest.getRequestURI().substring(contextPath.length());
                } else {
                        uri = httpServletRequest.getRequestURI();
                }


                logger.debug("processing web request, method: " + httpServletRequest.getMethod() + ", uri: " + uri);
                Boolean continueFilterChain = false;

                List<MimeType> acceptHeaderMimeTypes;

                try {
                        acceptHeaderMimeTypes = parseAcceptHeader(httpServletRequest.getHeader("Accept"));

                        for (Pattern s : ignoredUrlPatterns) {
                                if (s.matcher(uri).matches()) {
                                        logger.debug("url: " + uri + " found on ignore list");
                                        return true;
                                }
                        }

                        // default it to null, if you set it we'll know
                        httpServletResponse.setContentType(null);

                        final boolean isMultiPart = isMultiPart(httpServletRequest.getContentType());
                        final List<FileItem> uploads = FileUpload.parseFileUploads(httpServletRequest, propertiesFile);

                        ThreadBag.set(new ThreadVariables(this.context, httpServletRequest, httpServletResponse, routes, null, null, groovyClassLoader));
                          logger.debug("searching for matching route for uri: " + uri);

                        final sybrix.easygsp2.routing.Route route = findRoute(uri, httpServletRequest.getMethod());
                        if (route == null) {
                                if (httpServletRequest.getMethod().equalsIgnoreCase("GET")) {
                                        logger.warn("remember- unannotated get() requires an Id parameter {<\\d+$>id}, index() does not");
                                }
                                throw new NoMatchingRouteException("No route found for: " + uri);
                        }

                        String extension = StringUtil.extractFileExtension(uri);


                        Closure closure = new Closure(this) {

                                Class controllerClass = null;
                                boolean isApiController = false;

                                public Object call() {

                                        MimeType returnContentType = null;

                                        try {
                                                checkUserAuthorization(route, httpServletRequest);

                                                appListener.onRequestStart(httpServletRequest);

                                                String contentType = httpServletRequest.getContentType();
                                                int contentLength = httpServletRequest.getContentLength();

                                                Method m = null;

                                                controllerClass = route.getControllerClass();
                                                m = route.getControllerMethod();

                                                GroovyObject controller = ClassFactory.create(controllerClass, getOwner());

                                                List<String> s = lookupParameterNames(m, groovyClassLoader);
                                                Parameter[] parameters = m.getParameters();

                                                logger.debug("invoking method " + controllerClass.getName() + "." + m.getName() + "(), parameters: " + extractParameterTypes(parameters));
                                                logger.debug("parameter names: " + s);
                                                Enumeration<String> names = httpServletRequest.getHeaderNames();

                                                while (names.hasMoreElements()) {
                                                        String header = names.nextElement();
                                                        logger.debug("header name/value: " + header + ":" + httpServletRequest.getHeader(header));
                                                }

                                                Object[] params = new Object[parameters.length];

                                                int i = 0;
                                                for (Parameter p : parameters) {
                                                        String parameterName = p.getName();
                                                        if (s.size() >= parameters.length) {
                                                                parameterName = s.get(i);
                                                        }

                                                        Class clazz = p.getType();
                                                        Type genericType = extractGenericType(p);

                                                        if (clazz == javax.servlet.http.HttpServletRequest.class || parameterName.equalsIgnoreCase("request")) {
                                                                params[i] = httpServletRequest;

                                                        } else if (clazz == HttpServletResponse.class || parameterName.equalsIgnoreCase("response")) {
                                                                params[i] = httpServletResponse;
                                                        } else if ((clazz == List.class && genericType == FileItem.class) || parameterName.equalsIgnoreCase("uploads") && isMultiPart) {
                                                                params[i] = uploads;
                                                        } else if ((clazz == FileItem.class) && isMultiPart) {
                                                                params[i] = getFileItemByName(uploads, parameterName);
                                                        } else if (clazz == ServletContext.class || parameterName.equalsIgnoreCase("app")) {

                                                                params[i] = httpServletRequest.getServletContext();
                                                        } else if (clazz == RequestContext.class || parameterName.equalsIgnoreCase("context")) {
                                                                RequestContext requestContext = new RequestContext();
                                                                requestContext.setApp(httpServletRequest.getServletContext());
                                                                requestContext.setProperties(propertiesFile);
                                                                requestContext.setUserPrincipal(httpServletRequest.getUserPrincipal());
                                                                requestContext.setRequest(httpServletRequest);
                                                                requestContext.setResponse(httpServletResponse);

                                                                params[i] = requestContext;
                                                        } else if (clazz == PropertiesFile.class || parameterName.equalsIgnoreCase("propertiesFile")) {
                                                                params[i] = propertiesFile;

                                                        } else if (clazz == UserPrincipal.class || clazz == Principal.class) {

                                                                params[i] = httpServletRequest.getUserPrincipal();

                                                        } else {

                                                                String valFromRequestParameter = null;

                                                                if (route.getParameters().size() > 0) {
                                                                        UrlParameter urlParameter = route.getParameters().get(parameterName);
                                                                        if (urlParameter == null && m.getName().equalsIgnoreCase("get")) {
                                                                                logger.debug("unannotated method, did not find default parameter named \"id\", trying \"" + parameterName + "\" instead");
                                                                                urlParameter = route.getParameters().get("id");
                                                                                if (urlParameter == null) {
                                                                                        throw new BadRequestException("parameter name 'id' required. parameter name: " + parameterName);
                                                                                }
                                                                        }
                                                                        if (urlParameter.getValue() != null) {
                                                                                valFromRequestParameter = urlParameter.getValue();
                                                                        } else if (valFromRequestParameter == null) {
                                                                                valFromRequestParameter = httpServletRequest.getParameter(parameterName);
                                                                        }
                                                                } else {
                                                                        valFromRequestParameter = httpServletRequest.getParameter(parameterName);
                                                                }

                                                                if (valFromRequestParameter != null) {
                                                                        params[i] = castToType(valFromRequestParameter, clazz);

                                                                } else if (contentLength > 0 && isJson(contentType)) {

                                                                        Object obj = null;
                                                                        if (clazz.getTypeName().equals("java.lang.Object")) {
                                                                                obj = jsonSerializerInstance.parse(httpServletRequest.getInputStream(), contentLength);
                                                                        } else {

                                                                                obj = jsonSerializerInstance.parse(httpServletRequest.getInputStream(), contentLength, clazz, genericType);
                                                                        }
                                                                        params[i] = obj;

                                                                } else if (contentLength > 0 && isXML(contentType)) {
                                                                        Object obj = null;
                                                                        if (clazz.getTypeName().equals("java.lang.Object")) {
                                                                                obj = xmlSerializerInstance.fromXml(httpServletRequest.getInputStream(), contentLength);
                                                                        } else {
                                                                                obj = xmlSerializerInstance.fromXml(httpServletRequest.getInputStream(), contentLength, clazz);
                                                                        }
                                                                        params[i] = obj;

                                                                } else { //if (contentType != null && contentLength > 0 && isFormUrlEncoded(contentType) ||) {
                                                                        Object obj = clazz.newInstance();
                                                                        populateBean(obj, httpServletRequest);
                                                                        params[i] = obj;
                                                                }
                                                        }

                                                        i++;
                                                }

                                                //checkUserAuthorization(route, httpServletRequest);

                                                isApiController = isApiController(controllerClass);
                                                returnContentType = getContentReturnType(isApiController, httpServletResponse, acceptHeaderMimeTypes, route, extension);

                                                List errors = validateParameters(params);
                                                if (errors.size() > 0) {
                                                        throw new BadRequestException(errors);
                                                }

                                                Object _controllerResponse = invokeControllerAction(controller, m, params, route);

                                                ControllerResponse controllerResponse = ControllerResponse.parse(_controllerResponse);
                                                if (controllerResponse.forwarded()) {
                                                        httpServletRequest.getRequestDispatcher(controllerResponse.getUrl()).forward(httpServletRequest, httpServletResponse);
                                                        return false; // return now, don't continue filter chain.
                                                }

                                                if (controllerResponse.redirected()) {
                                                        httpServletResponse.sendRedirect(controllerResponse.getUrl());
                                                        return false; // return now, don't continue filter chain.
                                                }

                                                if (controllerResponse.getHttpCode() != null) {
                                                        httpServletResponse.setStatus(controllerResponse.getHttpCode());
                                                }

                                                logger.debug("controllers returned: " + _controllerResponse);

                                                Object redirectIssued = httpServletRequest.getAttribute("__redirect__");

                                                if (Boolean.TRUE.equals(redirectIssued)) {
                                                        return false;
                                                }

                                                logger.debug("return content type: " + returnContentType.toString());

                                                if (returnContentType.getBaseType().equals(APPLICATION_HTML_TEXT.getBaseType())) {
                                                        logger.debug("processing view " + _controllerResponse);
                                                        ThreadBag.get().getTemplateInfo().setErrorOccurred(false);
                                                        return processControllerResponse(controllerResponse, httpServletResponse, httpServletRequest);

                                                } else {
                                                        if (returnContentType.getBaseType().equals(APPLICATION_JSON.getBaseType())) {
                                                                //httpServletResponse.flushBuffer();

                                                                if (controllerResponse.getIsEntity() && controllerResponse.getEntity() != null) {
                                                                        logger.debug("sending json response to client");
                                                                        jsonSerializerInstance.write(controllerResponse.getEntity(), httpServletResponse.getOutputStream());
                                                                        //httpServletResponse.flushBuffer();
                                                                } else {
                                                                        logger.debug("controllers method returned null object, that's a 200 OK");
                                                                }
                                                        }
                                                }

                                                return false;

                                        } catch (UnauthorizedException e) {
                                                //if (e.getMessage().equalsIgnoreCase("EMAIL_NOT_VALIDATED")) {
                                                processException(e, true, returnContentType, httpServletRequest, httpServletResponse);
                                                //}
                                                return false;
                                        } catch (HttpException e) {
                                                logger.debug(e.getMessage(), e);
                                                processException(e, isApiController, returnContentType, httpServletRequest, httpServletResponse);

                                                return false;
                                        } catch (java.lang.IllegalArgumentException e) {
                                                logger.error("controllers", e);
//                                        } catch (NoMatchingRouteException | NoMatchingAcceptHeaderException | UnauthorizedException e) {
//                                                sendError(e.getStatus(), new CustomServletBinding(httpServletRequest.getServletContext(), httpServletRequest, httpServletResponse), e.getCause().getCause());
//                                                return false;

                                        } catch (Exception e) {
                                                logger.error(e.getMessage(), e);

                                                processException(new HttpException(e, HttpServletResponse.SC_INTERNAL_SERVER_ERROR), isApiController, returnContentType, httpServletRequest, httpServletResponse);

                                                return false;
                                        }

                                        return null;
                                }
                        };

                        continueFilterChain = (Boolean) GroovyCategorySupport.use(CustomServletCategory.class, closure);

                } catch (NoMatchingRouteException e) {
                        if (isContentTypeAccepted(httpServletRequest, APPLICATION_JSON)) {
                                processException(e, true, APPLICATION_JSON, httpServletRequest, httpServletResponse);
                        } else {
                                sendError(e.getStatus(), new CustomServletBinding(this.context, httpServletRequest, httpServletResponse), e);
                        }

                        continueFilterChain = false;
                } catch (InvokeControllerActionException e) {

                        sendError(500, new CustomServletBinding(this.context, httpServletRequest, httpServletResponse), e.getCause().getCause());
                        //throw new ServletException(e.getCause());
                } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                        sendError(500, new CustomServletBinding(this.context, httpServletRequest, httpServletResponse), e.getCause().getCause());
                        //throw new ServletException(e);
                } finally {
                        try {
                                if (appListener != null) {
                                        appListener.onRequestEnd(httpServletRequest);
                                }
                        } catch (Throwable e) {
                                e.printStackTrace();
                        }
                        ThreadBag.remove();
                }

                return continueFilterChain;
        }

        public MimeType getContentReturnType(Boolean apiRequest, HttpServletResponse httpServletResponse, List<MimeType> acceptHeaderMimeTypes, Route route, String extension) throws MimeTypeParseException {
                if (httpServletResponse.getContentType() == null) {

                        MimeType m = determineMimeTypeToReturn(apiRequest, acceptHeaderMimeTypes, route, extension);
                        httpServletResponse.setContentType(m.toString());

                        return m;

                } else {
                        return new MimeType(httpServletResponse.getContentType());
                }
        }

        public void processException(HttpException e, boolean apiRequest, MimeType returnContentType, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
                try {

                        httpServletResponse.setStatus(e.getStatus());


                        if (returnContentType.getBaseType().equals(APPLICATION_XML.getBaseType())) {
                                //msg = xmlSerializerInstance.toString(e.getConstraintErrors());
                                logger.error("IMPLEMENT XML toString on Serializer");
                        } else if (returnContentType.getBaseType().equals(APPLICATION_JSON.getBaseType()) && apiRequest) {
                                String msg = "";
                                if (e.getStatus() == 500) {
                                        ApiError apiError = new ApiError("Server error occurred.  Try again.");
                                        apiError.setCode(e.getStatus());
                                        if (e instanceof BadRequestException) {
                                                apiError.setMessages(((BadRequestException) e).getConstraintErrors());
                                        }

                                        msg = jsonSerializerInstance.toString(apiError);
                                } else {
                                        ApiError apiError = new ApiError(e.getMessage());
                                        if (e.getErrorCode() != null) {
                                                apiError.setCode(e.getErrorCode());
                                        } else {
                                                apiError.setCode(e.getStatus());
                                        }

                                        if (e instanceof BadRequestException) {
                                                apiError.setMessages(((BadRequestException) e).getConstraintErrors());
                                        }
                                        msg = jsonSerializerInstance.toString(apiError);
                                }

                                httpServletResponse.getOutputStream().write(msg.getBytes());

                        } else if (returnContentType.getBaseType().equals(APPLICATION_HTML_TEXT.getBaseType())) {
                                sendError(e.getStatus(), new CustomServletBinding(httpServletRequest.getServletContext(), httpServletRequest, httpServletResponse), extractException(e));
                        }

                } catch (Exception x) {
                        logger.debug("error occurred writing back to client on bad request", e);
                }
        }

        private Throwable extractException(HttpException e) {

                if (e.getCause() == null)
                        return e;
                else {
                        return e.getCause().getCause();
                }
        }

        private void checkUserAuthorization(Route route, EasyGspServletRequest httpServletRequest) throws UnauthorizedException {
                try {
                        if (route.isSecure()) {
                                UserPrincipal userPrincipal = (UserPrincipal) httpServletRequest.getUserPrincipal();
                                if (userPrincipal.getId() == null) {
                                        throw new UnauthorizedException();
                                }

                                if (route.getRoles().size() == 0) {
                                        // only authentication is required when route.getRoles.size ==0
                                        return;
                                }
                                // check for role
                                for (String role : route.getRoles()) {
                                        if (httpServletRequest.isUserInRole(role)) {
                                                return;
                                        }
                                }
                                throw new UnauthorizedException();
                        }

                } catch (ExpiredJwtException | NullPointerException e) {
                        throw new UnauthorizedException();
                }
        }

        public static Cookie getCookie(String cookieName, HttpServletRequest request) {

                Cookie c[] = request.getCookies();
                if (c != null) {
                        for (int x = 0; x < c.length; x++) {
                                if (c[x].getName().equals(cookieName)) {
                                        return c[x];
                                }
                        }
                }
                return null;
        }

        private List<ValidationMessage> validateParameters(Object[] params) {

                List constraintViolations = new ArrayList();

                for (Object o : params) {
                        if (o == null || o instanceof ServletContext || o instanceof FileItem || o instanceof ServletRequest || o instanceof ServletResponse) {
                                continue;
                        }

                        Set _constraintViolations = validator.validate(o);
                        for (Object c : _constraintViolations) {
                                ConstraintViolation constraintViolation = ((ConstraintViolation) c);
                                ValidationMessage vm = new ValidationMessage(constraintViolation.getPropertyPath().toString(), constraintViolation.getMessage());
                                constraintViolations.add(vm);
                        }
                }

                return constraintViolations;
        }

        private MimeType determineMimeTypeToReturn(Boolean apiRequest, List<MimeType> accepts, Route route, String extension) throws MimeTypeParseException {
                if (accepts == null) {
                        accepts = new ArrayList<MimeType>();
                }

                if (accepts.size() == 0) {
                        if (apiRequest == true) {
                                return MimeTypes.getMimeTypes().get(".json");
                        } else {
                                return MimeTypes.getMimeTypes().get(".html");
                        }
                }

                if (!isEmpty(extension) && route.getProducesMimeType().size() == 0) {
                        if (MimeTypes.getMimeTypes().containsKey(extension)) {
                                return MimeTypes.getMimeTypes().get(extension);
                        }
                }
                if (apiRequest) {
                        route.setProduces(new String[]{propertiesFile.getString("default.response.contentType", MediaType.JSON)});
                }

                for (MimeType accept : accepts) {
                        MimeType m = isContentTypeAccepted(accept, route.getProducesMimeType());
                        if (m != null) {
                                return m;
                        }
                }

                throw new UnsupportedContentType("");
                // check method
                // check class
                // check default return type
        }

        private MimeType calculateContentType(ControllerResponse controllerResponse, sybrix.easygsp2.routing.Route route, HttpServletResponse response, HttpServletRequest request, Boolean isApiCall) throws MimeTypeParseException {


                // if there's only one, the use it
                if (route.getProduces().length == 1) {
                        return new MimeType(route.getProduces()[0]);
                } else if (route.getProduces().length == 0 && controllerResponse.getIsString()) {
                        return APPLICATION_HTML_TEXT;
                }

                // if you set it manually use it
                if (response.getContentType() != null && response.getContentType().length() > 0) {
                        return new MimeType(response.getContentType());
                }

                MimeType defaultMimeType = null;
                if (isApiCall) {
                        defaultMimeType = new MimeType(propertiesFile.getString("api.default.response.contentType", "application/json"));
                } else {
                        defaultMimeType = new MimeType(propertiesFile.getString("default.response.contentType", "text/html"));
                }

                // if you don't set, figure it out based on what you returned from the method
                if (route.getProduces().length == 0) {
                        if (controllerResponse.getIsJsonSluper()) {
                                return APPLICATION_JSON;
                        } else if (controllerResponse.getIsXMLSlurper()) {
                                return APPLICATION_XML;
                        } else {
                                if (defaultMimeType.getBaseType().equals(APPLICATION_JSON.getBaseType())) {
                                        return APPLICATION_JSON;
                                } else if (defaultMimeType.getBaseType().equals(APPLICATION_XML.getBaseType())) {
                                        return APPLICATION_XML;
                                } else {
                                        return defaultMimeType;
                                }
                        }
                }

                return defaultMimeType;
        }

        private List<MimeType> parseAcceptHeader(String acceptHeader) {

                List<MimeType> accepts = new ArrayList();
                String header = null;
                if (acceptHeader != null) {
                        try {
                                String[] parts = acceptHeader.split(",");
                                for (String s : parts) {
                                        header = s;
                                        accepts.add(new MimeType(header));
                                }
                        } catch (MimeTypeParseException e) {
                                logger.debug("MimeTypeParseException, " + e.getMessage() + ", acceptHeader Param:" + acceptHeader + ", bad header: " + header);
                        }
                }

                return accepts;

        }

        //                private boolean isContentTypeAccepted(){
//
//                String accept = request.getHeader("Accept");
//                String[] acceptsHeader = {};
//
//                if (accept != null) {
//                        acceptsHeader = accept.split(",");
//                }
//
//                // no accepts header
//                if (acceptsHeader == null) {
//                        acceptsHeader = route.getProduces().length == 0 ? defaultContentType.split(",") : route.getProduces()[0].split(",");
//                }
//
//                String[] returns = route.getProduces();
//                String[] returnContentType = null;
//                if (returns.length == 0) {
//                        returnContentType = defaultContentType.split(",");
//                }
//
//
//                MimeType mimeType = findMimeTypeMatch(acceptsHeader, returnContentType);
//
//                if (mimeType != null) {
//                        response.setContentType(mimeType.toString());
//                        return mimeType.toString();
//                } else {
//                        logger.info("unable to determine response content type");
//                        return null;
//                }
//
//        }

        private MimeType isContentTypeAccepted(MimeType sought, List<MimeType> listToSearch) {
                if (listToSearch.size() == 0) {
                        logger.debug("no \"produces\" in @Api annotation, letting it through");
                        return null;
                }
                for (MimeType m : listToSearch) {
                        if (sought.match(m) || m.getPrimaryType().equals("*") || sought.getPrimaryType().equals("*")) {
                                return m;
                        }
                }

                if (sought.getPrimaryType().equals("*") && listToSearch.size() == 0) {
                        return sought;
                }

                return null;
        }

        private boolean isContentTypeAccepted(HttpServletRequest request, MimeType soughtMimeType) {
                try {
                        if (request.getHeader("Accept") == null) {
                                return true;
                        }

                        for (String r : request.getHeader("Accept").split(",")) {
                                MimeType mimeType = new MimeType(r);

                                if (soughtMimeType.match(mimeType) || mimeType.getPrimaryType().equals("*")) {
                                        return true;
                                }
                        }

                        return false;

                } catch (Exception e) {
                        logger.debug(e.getMessage(), e);
                        return false;
                }
        }


        private MimeType findMimeTypeMatch(String[] acceptsHeader, String[] returnContentType) {
                try {
                        if (returnContentType == null) {
                                return null;
                        }
                        for (String r : returnContentType) {
                                MimeType returnMime = new MimeType(r);
                                for (String a : acceptsHeader) {
                                        if (returnMime.match(a)) {
                                                return returnMime;
                                        }
                                }
                        }
                } catch (Exception e) {
                        e.printStackTrace();
                }

                return null;
        }

        private boolean compare(String s1, String s2) {
                return s1.trim().toLowerCase().equals(s2.trim().toLowerCase());
        }

        private FileItem getFileItemByName(List<FileItem> uploads, String parameterName) {
                for (FileItem i : uploads) {
                        if (i.getFieldName().equals(parameterName)) {
                                return i;
                        }
                }

                return null;
        }

        private Type extractGenericType(Parameter p) {
                try {
                        return ((ParameterizedTypeImpl) p.getParameterizedType()).getActualTypeArguments()[0];
                } catch (Exception e) {
                        return null;
                }
        }

        private boolean processControllerResponse(ControllerResponse controllerResponse, HttpServletResponse httpServletResponse, HttpServletRequest httpServletRequest) throws URISyntaxException, IOException, ServletException {
                ThreadBag.get().setTemplateRequest(true);
                Binding binding = ThreadBag.get().getBinding();

                if (binding == null) {
                        binding = new CustomServletBinding(context, httpServletRequest, httpServletResponse);
                }

                if (controllerResponse.getIsString() && controllerResponse.getEntity().toString().endsWith(".jsp") && isServlet) {

                        RequestDispatcher rd = httpServletRequest.getServletContext().getRequestDispatcher("/indexs.jsp");
                        rd.forward(httpServletRequest, httpServletResponse);

                } else if (controllerResponse.getIsString() && controllerResponse.getEntity().toString().endsWith(".jsp") && isServlet == false) {
                        RequestDispatcher rd = httpServletRequest.getServletContext().getRequestDispatcher("/indexs.jsp");
                        rd.forward(httpServletRequest, httpServletResponse);
                        return true;
                } else if (controllerResponse.getIsString() && !controllerResponse.getEntity().toString().endsWith(".jsp")) {

                        URL f = Thread.currentThread().getContextClassLoader().getResource("./../../WEB-INF/views/" + controllerResponse.getViewTemplate());

                        if (f == null) {
                                throw new NoViewTemplateFound("View template  '" + controllerResponse.toString() + "' not found!!!");
                        }

                        ThreadBag.get().setViewFolder(f.getPath());
                        File requestedViewFile = new File(f.toURI());
                        ThreadBag.get().setTemplateFilePath(requestedViewFile.getAbsolutePath());

                        ThreadBag.get().getTemplateInfo().setRequestUri(controllerResponse.toString());
                        ThreadBag.get().getTemplateInfo().setAppFolderClassPathLocation("./../../WEB-INF/views/");
                        ThreadBag.get().getTemplateInfo().setRequestFile(requestedViewFile);

                        try {
                                TemplateWriter templateWriter = new TemplateWriter(groovyClassLoader);
                                templateWriter.process(httpServletResponse, ThreadBag.get().getTemplateInfo(), binding);
                                return false;
                        } catch (HttpException e) {
                                logger.debug(e.getMessage(), e);
                                sendError(e.getStatus(), binding, e.getCause());
                                return false;
                        } catch (Throwable e) {
                                logger.debug(e.getMessage(), e);
                                sendError(500, binding, e.getCause());
                                return false;
                        }
                }

                return true;
        }


        protected void sendError(int httpStatusCode, Binding binding, Throwable e) {
                try {

                        ThreadBag.get().getTemplateInfo().setErrorOccurred(true);
                        try {
                                //  ThreadBag.get().getResponse().sendError(errorCode, e.getMessage());
                        } catch (IllegalStateException il) {

                        }
                        ThreadBag.get().getResponse().setStatus(httpStatusCode);
                        RequestError requestError = ThreadBag.get().getRequestError();
                        requestError.setErrorCode(httpStatusCode);
                        requestError.setScriptPath(ThreadBag.get().getTemplateFilePath());
                        requestError.setErrorMessage(e.getMessage() == null ? "" : e.getMessage());
                        requestError.setException(e, "", "");

                        StackTraceElement stackTraceElement = findErrorInStackTrace(binding, e);
                        String errorSource = highlightErrorSource(stackTraceElement);
                        requestError.setSource(errorSource.toString());

                        String path = ThreadBag.get().getTemplateFilePath();//.replace(appPath, "").replace(appPath2, "");
                        if (stackTraceElement != null) {

                                String lineNumberMessage = "Error occurred in <span class=\"path\">" + path + "</span> @ lineNumber: " + (stackTraceElement.getLineNumber());
                                requestError.setLineNumberMessage(lineNumberMessage);
                                requestError.setLineNumber(stackTraceElement.getLineNumber());
                        } else {
                                requestError.setLineNumberMessage("");
                                if (ThreadBag.get().isTemplateRequest()) {
                                        String lineNumberMessage = "Error occurred in <span class=\"path\">" + path + "</span>";
                                        requestError.setLineNumberMessage(lineNumberMessage);
                                }
                                requestError.setLineNumber(-1);
                        }

                        File errorFile = getErrorFileContents(httpStatusCode);
                        ThreadBag.get().getTemplateInfo().setRequestFile(errorFile);

                        binding.setVariable("exceptionName", e.getClass().getName());
                        binding.setVariable("requestError", requestError);
                        processErrorTemplateRequest(ThreadBag.get().getResponse(), ThreadBag.get().getTemplateInfo(), binding);

                        ThreadBag.get().getResponse().flushBuffer();

                } catch (Throwable e1) {
                        logger.debug(e1.getMessage(), e1);
                        //sendError(errorCode, response, e, stackTraceElement, binding);
                }
        }

        private String highlightErrorSource(StackTraceElement stackTraceElement) {
                StringBuffer lineBuffer = new StringBuffer();

                if (stackTraceElement != null) {
                        try {
                                File f = new File(ThreadBag.get().getTemplateFilePath());
                                BufferedReader br = new BufferedReader(new FileReader(f));
                                String line = null;

                                int lineCt = 1;

                                while ((line = br.readLine()) != null) {
                                        if (lineCt >= stackTraceElement.getLineNumber() - 10 && (lineCt <= stackTraceElement.getLineNumber() + 10)) {
                                                lineBuffer.append("<div class=\"sourceLine\"><div class=\"sourceLineNumber\">").append(lineCt).append("</div>");
                                                lineBuffer.append("<div class=\"sourceCode\">");
                                                lineBuffer.append(line.length() == 0 ? "&nbsp;" : StringUtil.htmlEncode(line).replaceAll(" ", "&nbsp;"));
                                                lineBuffer.append("</div></div>");
                                        }

                                        lineCt++;
                                }

                                br.close();
                        } catch (Exception ex) {
                                logger.debug("Unable to parse source code to display on error page", ex);
                        }
                }

                return lineBuffer.toString();
        }

        private File getErrorFileContents(int errorCode) throws IOException {
                String defaultTemplateExtension = propertiesFile.getString("default.template.extension", "gsp");
                String tmpDir = propertiesFile.getString("easygsp.tmp.dir", System.getProperty("java.io.tmpdir"));

                InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("./errors/error" + errorCode + "." + defaultTemplateExtension);
                File errorFile = new File(tmpDir + File.separator + "errors" + File.separator + "error" + errorCode + "." + defaultTemplateExtension);

                // createFile if it doesn't exist
                if (!errorFile.exists()) {
                        new File(tmpDir + File.separator + "errors").mkdir();
                        FileOutputStream outputStream = new FileOutputStream(errorFile);

                        byte[] buff = new byte[1024];

                        while (true) {
                                int len = inputStream.read(buff, 0, buff.length);
                                if (len < 0)
                                        break;
                                outputStream.write(buff, 0, len);
                        }
                        outputStream.close();
                }

                return errorFile;
        }

        private void processErrorTemplateRequest(HttpServletResponse httpServletResponse, TemplateInfo templateInfo, Binding binding) {
                try {
                        TemplateWriter templateWriter = new TemplateWriter(groovyClassLoader);
                        templateWriter.process(httpServletResponse, templateInfo, binding);

                } catch (Exception e) {
                        e.printStackTrace();
                }
        }


        private static StackTraceElement findErrorInStackTrace(Binding binding, Throwable e) {

                if (ThreadBag.get().isTemplateRequest()) {
                        if (e.getStackTrace() != null && ThreadBag.get().getTemplateInfo() != null) {

                                for (StackTraceElement stackTraceElement : e.getStackTrace()) {
                                        if (stackTraceElement.getFileName() != null) {
                                                if (stackTraceElement.getFileName().startsWith(ThreadBag.get().getTemplateInfo().getUniqueTemplateScriptName())) {
                                                        return stackTraceElement;
                                                }
                                        }
                                }
                        }
                } else {
//                        String appPath = RequestThreadInfo.get().getApplication().getAppPath();
//                        String currentFile = ThreadBag.get().getTemplateFilePath().substring(appPath.length() + 1);
//
//                        for (StackTraceElement stackTraceElement : e.getStackTrace()) {
//                                if (stackTraceElement.getFileName() != null) {
//                                        if (stackTraceElement.getFileName().endsWith(currentFile)) {
//                                                return stackTraceElement;
//                                        }
//                                }
//                        }
                }

                return null;
        }

        private void loadPropertiesIntoContext(ServletContext app, PropertiesFile propertiesFile) {
                for (String s : propertiesFile.stringPropertyNames()) {
                        app.setAttribute(s, propertiesFile.get(s));

                        if (s.matches("ignore\\.url\\.pattern\\.\\d+$")) {
                                ignoredUrlPatterns.add(Pattern.compile(propertiesFile.getString(s)));
                        }
                }
        }

        private void loadUnannotatedClasses(PropertiesFile propertiesFile, String packageName) {
                logger.debug("loading unannotated controllers classes");
                String defaultPackageName = propertiesFile.getString(packageName);

                Reflections reflections = new Reflections(defaultPackageName, new TypeElementsScanner(), new SubTypesScanner(false));
                Set<String> types = reflections.getAllTypes();

                for (String cls : types) {
                        if (classesWithApiAnnotation.contains(cls)) {
                                //    continue;
                        }

                        if (cls.endsWith("Controller")) {
                                logger.debug("parsing class: " + cls);
                                StringBuffer sb = new StringBuffer();

                                String _api = cls.substring(defaultPackageName.length() + 1, cls.length() - "Controller".length());
                                for (String s : _api.split("\\.")) {
                                        sb.append(s.substring(0, 1).toLowerCase());
                                        sb.append(s.substring(1, s.length()));
                                        sb.append("/");
                                }
                                String api = sb.substring(0, sb.length() - 1);
                                String pattern = "/" + api;

                                Class clazz = parseClassName(cls);

                                String[] classMethods = {"list", "index", "get", "put", "post", "delete"};

                                if (clazz != null) {
                                        String mediaType[] = {propertiesFile.getString("default.response.contentType", MediaType.HTML)};

                                        String[] accepts = mediaType;
                                        String[] returns = determineDefaultReturnType(clazz);
                                        boolean isApiController = isApiController(clazz);

                                        Secured securedAnno = (Secured) clazz.getAnnotation(Secured.class);
                                        String[] classRoles = securedAnno == null ? new String[]{} : securedAnno.value();

                                        for (String classMethod : classMethods) {
                                                String _pattern = pattern;
                                                String _method = classMethod;

                                                if (classMethod.equalsIgnoreCase("list")) {
                                                        if (isApiController) {
                                                                _method = "list";
                                                        } else {
                                                                _method = "index";
                                                        }
                                                }

                                                if (classMethod.equalsIgnoreCase("DELETE") || classMethod.equalsIgnoreCase("GET")) {
                                                        _pattern = pattern + "/{<\\d+$>id}";
                                                }

                                                String httpMethod = (classMethod.equalsIgnoreCase("list") || classMethod.equalsIgnoreCase("index")) ? "GET" : classMethod.toUpperCase();

                                                if (!Routes.contains(_pattern, httpMethod)) {
                                                        MethodAndRole method = extractMethod(clazz, _method);
                                                        if (method.getSecure() == false) {
                                                                method.setSecure(securedAnno != null);
                                                        }

                                                        if (method.getMethod() != null) {
                                                                String[] roles = combine(method.getRoles(), classRoles);

                                                                Routes.add(method.getMethod(), _pattern, httpMethod, new Class[]{}, roles, accepts, returns, method.getSecure());
                                                                //route.setSecure();
                                                        }
                                                }
                                        }

                                }
                        } else {
                                logger.warn("class: " + cls + " doesn't end with 'Controller', skipping as controllers");
                        }
                }
        }


        private boolean isApiController(Class clazz) {

                String apiControllerPackage = propertiesFile.getString("api.controllers.package", "");

                if (clazz.getPackage().getName().equals(apiControllerPackage)) {
                        return true;
                }

                return false;
        }

        private String[] determineDefaultReturnType(Class clazz) {
                Content content = null;
                try {
                        content = (Content) clazz.getAnnotation(Content.class);
                        if (content != null) {
                                String returnType = content.returns();

                                if (returnType != null) {
                                        return new String[]{returnType};
                                }
                        }
                } catch (Exception e) {
                        logger.debug("unable to get Content annotation form class " + clazz.getName());
                }

                String controllerPackage = propertiesFile.getString("controllers.package", "");
                String apiControllerPackage = propertiesFile.getString("api.controllers.package", "");

                if (clazz.getPackage().getName().equals(controllerPackage)) {
                        return new String[]{propertiesFile.getString("default.response.contentType", MediaType.JSON)};
                } else if (clazz.getPackage().getName().equals(apiControllerPackage)) {
                        return new String[]{propertiesFile.getString("api.default.response.contentType", MediaType.HTML)};
                }

                return new String[]{};
        }

        private Class parseClassName(String cls) {
                try {
                        return Class.forName(cls);
                } catch (ClassNotFoundException e) {
                        return null;
                }
        }

        private MethodAndRole extractMethod(Class cls, String methodName) {
                MethodAndRole methodAndRole = new MethodAndRole();

                try {
                        Method foundMethod = null;
                        for (Method m : cls.getDeclaredMethods()) {
                                if (m.getName().equals(methodName)) {
                                        if (foundMethod == null) {
                                                foundMethod = m;
                                        } else {
                                                logger.error("method " + methodName + "() found in class: " + cls.getName() + ", not allowed in when using routing by naming convention only.  Use @api annotation instead.");
                                                break;
                                        }
                                }
                        }

                        if (foundMethod == null) {
                                return methodAndRole;
                        }

                        Method m = foundMethod;
                        methodAndRole.setMethod(m);

                        Annotation annotation = m.getDeclaredAnnotation(Secured.class);
                        if (annotation != null) {
                                methodAndRole.setSecure(true);
                                String[] roles = ((Secured) annotation).value();
                                methodAndRole.setRoles(roles);
                        }

                } catch (Exception e) {
                        logger.debug(methodName.toLowerCase() + "() method not found in class " + cls.getName());
                        return methodAndRole;
                }

                return methodAndRole;
        }

        private void loadApiMethods(PropertiesFile propertiesFile) {
                logger.debug("loading annotated controllers methods...");
                Reflections reflections = new Reflections("", new MethodAnnotationsScanner(), new SubTypesScanner());
                Set<Method> methods = reflections.getMethodsAnnotatedWith(Api.class);

                for (Method m : methods) {

                        Api classAnno = extractClassAnnotation(m.getDeclaringClass().getDeclaredAnnotations());
                        classesWithApiAnnotation.add(m.getDeclaringClass().getName());

                        Api methodAnno = m.getDeclaredAnnotation(Api.class);

                        Secured securedClassAnno = (Secured) m.getDeclaringClass().getAnnotation(Secured.class);
                        String[] classRolesFromSecureAnnotation = securedClassAnno == null ? new String[]{} : securedClassAnno.value();

                        Secured securedMethodAnno = m.getDeclaredAnnotation(Secured.class);
                        String[] methodRolesFromSecureAnnotation = securedMethodAnno == null ? new String[]{} : securedMethodAnno.value();

                        boolean secured = (securedMethodAnno != null || securedClassAnno != null);

                        if (methodAnno.url().length > 0) {
                                logger.debug("loading class: " + m.getDeclaringClass().getName() + ", method: " + m.getName() + "(), url: " + methodAnno.url());

                                for (String classPattern : classAnno.url()) {
                                        for (String _pattern : methodAnno.url()) {

                                                String pattern = classPattern + _pattern;

                                                String[] httpMethods = combine(methodAnno.method(), classAnno.method());
                                                for (String httpMethod : httpMethods) {
                                                        if (httpMethod.equals("*")) {
                                                                httpMethods = sybrix.easygsp2.routing.Route.HTTP_METHODS;
                                                                break;
                                                        }
                                                }

                                                for (String httpMethod : httpMethods) {
                                                        String[] securedRoles = combine(classRolesFromSecureAnnotation, methodRolesFromSecureAnnotation);
                                                        String[] roles = combine(methodAnno.roles(), securedRoles, classAnno.roles());

                                                        String[] accepts = methodAnno.accepts() != null ? classAnno.accepts() : methodAnno.accepts();
                                                        String[] returns = methodAnno.contentType() != null ? classAnno.contentType() : methodAnno.contentType();
                                                        if (returns.length == 0) {
                                                                returns = determineDefaultReturnType(m.getDeclaringClass());
                                                        }

                                                        Routes.add(m, pattern, httpMethod, null, roles, accepts, returns, secured);
                                                }
                                        }
                                }

                        } else {
                                logger.info("no patterns found for method " + m);
                        }
                }
        }

        private void loadClaudAPIDocs(PropertiesFile propertiesFile) {
                try {
                        String claudeTemplate = propertiesFile.getString("claude.md.template.path");
                        String claudeOutputPath = propertiesFile.getString("claude.md.out.path");
                        String startToken = propertiesFile.getString("claude.md.start.token");
                        String endToken = propertiesFile.getString("claude.md.end.token");

                        if (claudeTemplate == null || claudeOutputPath == null || startToken == null || endToken == null) {
                                return;
                        }

                        logger.debug("loading annotated controllers for Claude docs...");
                        Reflections reflections = new Reflections("", new MethodAnnotationsScanner(), new SubTypesScanner());
                        Set<Method> methods = reflections.getMethodsAnnotatedWith(Api.class);
                        List<ClaudeAPI> claudeDocs = new ArrayList<ClaudeAPI>();

                        for (Method m : methods) {
                                ClaudeAPI claudeAPI = new ClaudeAPI();

                                Api classAnno = extractClassAnnotation(m.getDeclaringClass().getDeclaredAnnotations());
                                classesWithApiAnnotation.add(m.getDeclaringClass().getName());

                                Api methodAnno = m.getDeclaredAnnotation(Api.class);
                                if (isEmpty(methodAnno.description())){
                                        continue;
                                }
                                claudeAPI.setDescription(methodAnno.description());
                                claudeAPI.setMethod(StringUtil.commaSeparate(Arrays.asList(methodAnno.method())).toUpperCase());
                                claudeAPI.setPath(StringUtil.commaSeparate(Arrays.asList(methodAnno.url())));
                                try {
                                        // Get return type information
                                        Class<?> returnType = m.getReturnType();
                                        Type genericReturnType = m.getGenericReturnType();

                                        // Check for circular references in the return type
                                        if (!returnType.isPrimitive() && !returnType.equals(Void.TYPE) &&
                                            !returnType.equals(String.class) && !Number.class.isAssignableFrom(returnType) &&
                                            !returnType.equals(Boolean.class)) {
                                                try {
                                                        boolean hasCircularRefs = JsonTypeConverter.hasCircularReferences(returnType);
                                                        claudeAPI.setHasCircularReferences(hasCircularRefs);
                                                } catch (Exception ex) {
                                                        logger.debug("Could not check for circular references in type: " + returnType.getName(), ex);
                                                }
                                        }

                                        // For primitive types and simple types, use the string representation
                                        if (returnType.equals(Void.TYPE) || returnType.equals(void.class)) {
                                                claudeAPI.setReturnType("void");
                                        } else if (returnType.isPrimitive() ||
                                                   returnType.equals(String.class) ||
                                                   Number.class.isAssignableFrom(returnType) ||
                                                   returnType.equals(Boolean.class)) {
                                                // For simple types, use the JSON type name as a string
                                                String jsonType = JsonTypeConverter.getJsonType(returnType, genericReturnType);
                                                claudeAPI.setReturnType(jsonType);
                                        } else {
                                                // For complex types, generate the object structure
                                                try {
                                                        // Use the new method that returns an Object (Map) for proper template rendering
                                                        Object jsonStructure = JsonTypeConverter.classToJsonTypesAsObject(returnType);
                                                        claudeAPI.setReturnType(jsonStructure);
                                                } catch (Exception ex) {
                                                        // If that fails, fall back to the simple type name
                                                        String typeName = JsonTypeConverter.getJsonType(returnType, genericReturnType);
                                                        claudeAPI.setReturnType(typeName);
                                                }
                                        }
                                } catch (Exception e) {
                                        logger.debug("Could not determine return type for method: " + m.getName(), e);
                                        claudeAPI.setReturnType("object");
                                }

                                Secured securedMethodAnno = m.getDeclaredAnnotation(Secured.class);
                                boolean secured = (securedMethodAnno != null);
                                claudeAPI.setAuthenticateRequired(secured);

                                Params qsParams = m.getAnnotation(Params.class);
                                if (qsParams != null) {
                                        for (Param param : qsParams.value()) {
                                                if (param !=null) {
                                                        ClaudeAPIParameter claudeAPIParameter = new ClaudeAPIParameter();
                                                        claudeAPIParameter.setName(param.name());
                                                        claudeAPIParameter.setRequired(param.required());
                                                        claudeAPIParameter.setType(param.type());
                                                        claudeAPIParameter.setDescription(param.description());

                                                        if (param.queryString()) {
                                                                claudeAPI.getQueryStringParameters().add(claudeAPIParameter);
                                                        } else {
                                                                claudeAPI.getPathParameters().add(claudeAPIParameter);
                                                        }
                                                }
                                        }
                                }


                                Parameter[] methodParams = m.getParameters();
                                if (methodParams != null) {
                                        for (Parameter parameter : methodParams) {
                                                if (parameter.isAnnotationPresent(Param.class)) {
                                                        Param param = parameter.getAnnotation(Param.class);
                                                        if (param != null) {
                                                                ClaudeAPIParameter claudeAPIParameter = new ClaudeAPIParameter();
                                                                claudeAPIParameter.setName(param.name());
                                                                claudeAPIParameter.setRequired(param.required());
                                                                claudeAPIParameter.setType(JsonTypeConverter.getJsonType(parameter.getType(), null));
                                                                claudeAPIParameter.setDescription(param.description());
                                                                claudeAPI.getParameters().add(claudeAPIParameter);
                                                        }
                                                }
                                        }
                                }
                                // BUGFIX: Move claudeDocs.add outside the methodParams check
                                // so methods without parameters are also documented
                                claudeDocs.add(claudeAPI);
                        }


                        Map binding = new HashMap();
                        binding.put("claudeDocs", claudeDocs);
                        String s = buildTemplate(claudeTemplate, binding);

                        replaceTextInFile(claudeOutputPath, startToken, endToken, s);
                }catch (Exception e){
                        logger.error("writing claude docs failed. " + e.getMessage(), e);
                }
        }

        /**
         * Enhanced method to properly scan controller methods for annotations and generate API documentation.
         * This method provides a more comprehensive approach to generating Claude API documentation.
         *
         * @param propertiesFile The properties file containing configuration for Claude documentation
         */
        public void generateEnhancedClaudeAPIDocs(PropertiesFile propertiesFile) {
                try {
                        String claudeOutputPath = propertiesFile.getString("claude.output");
                        String claudeTemplate = propertiesFile.getString("claude.template");
                        String startToken = propertiesFile.getString("claude.startToken", "<!-- start docs -->");
                        String endToken = propertiesFile.getString("claude.endToken", "<!-- end docs -->");

                        if (claudeOutputPath == null || claudeTemplate == null) {
                                logger.info("Claude docs not configured. Skipping generation.");
                                return;
                        }

                        List<ClaudeAPI> claudeDocs = new ArrayList<>();

                        // Get all classes with @Api annotations
                        Reflections reflections = new Reflections("",
                                new SubTypesScanner(false),
                                new TypeElementsScanner(),
                                new MethodAnnotationsScanner());

                        Set<Method> methodsWithApiAnnotation = reflections.getMethodsAnnotatedWith(Api.class);

                        for (Method method : methodsWithApiAnnotation) {
                                ClaudeAPI claudeAPI = new ClaudeAPI();

                                // Get Api annotation from method
                                Api apiAnnotation = method.getAnnotation(Api.class);

                                // Set basic properties
                                claudeAPI.setDescription(apiAnnotation.description());
                                claudeAPI.setProtocol("https");

                                // Handle URLs - support multiple URLs
                                if (apiAnnotation.url() != null && apiAnnotation.url().length > 0) {
                                        claudeAPI.setPath(StringUtil.commaSeparate(Arrays.asList(apiAnnotation.url())));
                                }

                                // Handle HTTP methods - support multiple methods
                                if (apiAnnotation.method() != null && apiAnnotation.method().length > 0) {
                                        claudeAPI.setMethod(StringUtil.commaSeparate(Arrays.asList(apiAnnotation.method())).toUpperCase());
                                }

                                // Check if authentication is required
                                boolean classSecured = method.getDeclaringClass().isAnnotationPresent(Secured.class);
                                boolean methodSecured = method.isAnnotationPresent(Secured.class);
                                claudeAPI.setAuthenticateRequired(classSecured || methodSecured);

                                // Set return type information
                                try {
                                        // Get return type information
                                        Class<?> returnType = method.getReturnType();
                                        Type genericReturnType = method.getGenericReturnType();

                                        // Check for circular references in the return type
                                        if (!returnType.isPrimitive() && !returnType.equals(Void.TYPE) &&
                                            !returnType.equals(String.class) && !Number.class.isAssignableFrom(returnType) &&
                                            !returnType.equals(Boolean.class)) {
                                                try {
                                                        boolean hasCircularRefs = JsonTypeConverter.hasCircularReferences(returnType);
                                                        claudeAPI.setHasCircularReferences(hasCircularRefs);
                                                } catch (Exception ex) {
                                                        logger.debug("Could not check for circular references in type: " + returnType.getName(), ex);
                                                }
                                        }

                                        // For primitive types and simple types, use the string representation
                                        if (returnType.equals(Void.TYPE) || returnType.equals(void.class)) {
                                                claudeAPI.setReturnType("void");
                                        } else if (returnType.isPrimitive() ||
                                                   returnType.equals(String.class) ||
                                                   Number.class.isAssignableFrom(returnType) ||
                                                   returnType.equals(Boolean.class)) {
                                                // For simple types, use the JSON type name as a string
                                                String jsonType = JsonTypeConverter.getJsonType(returnType, genericReturnType);
                                                claudeAPI.setReturnType(jsonType);
                                        } else {
                                                // For complex types, generate the object structure
                                                try {
                                                        // Use the new method that returns an Object (Map) for proper template rendering
                                                        Object jsonStructure = JsonTypeConverter.classToJsonTypesAsObject(returnType);
                                                        claudeAPI.setReturnType(jsonStructure);
                                                } catch (Exception ex) {
                                                        // If that fails, fall back to the simple type name
                                                        String typeName = JsonTypeConverter.getJsonType(returnType, genericReturnType);
                                                        claudeAPI.setReturnType(typeName);
                                                }
                                        }
                                } catch (Exception e) {
                                        logger.debug("Could not determine return type for method: " + method.getName(), e);
                                        claudeAPI.setReturnType("object");
                                }

                                // Process @Params annotation (for query string parameters)
                                Params paramsAnnotation = method.getAnnotation(Params.class);
                                if (paramsAnnotation != null) {
                                        for (Param param : paramsAnnotation.value()) {
                                                if (param != null) {
                                                        if (param.queryString()) {
                                                                ClaudeAPIParameter claudeAPIParameter = new ClaudeAPIParameter();
                                                                claudeAPIParameter.setName(param.name());
                                                                claudeAPIParameter.setRequired(param.required());
                                                                claudeAPIParameter.setType(param.type());
                                                                claudeAPIParameter.setDescription(param.description());
                                                                claudeAPI.getQueryStringParameters().add(claudeAPIParameter);
                                                        }
                                                }
                                        }
                                }

                                // Process individual @Param annotations (repeatable)
                                Param[] individualParams = method.getAnnotationsByType(Param.class);
                                for (Param param : individualParams) {
                                        // Skip if already processed via @Params
                                        boolean alreadyProcessed = claudeAPI.getQueryStringParameters().stream()
                                                .anyMatch(p -> p.getName().equals(param.name()));

                                        if (!alreadyProcessed) {
                                                ClaudeAPIParameter claudeAPIParameter = new ClaudeAPIParameter();
                                                claudeAPIParameter.setName(param.name());
                                                claudeAPIParameter.setRequired(param.required());
                                                claudeAPIParameter.setType(param.type());
                                                claudeAPIParameter.setDescription(param.description());
                                                claudeAPI.getQueryStringParameters().add(claudeAPIParameter);
                                        }
                                }

                                // Process method parameters
                                Parameter[] methodParams = method.getParameters();
                                if (methodParams != null) {
                                        for (Parameter parameter : methodParams) {
                                                // Skip special framework parameters
                                                if (isFrameworkParameter(parameter.getType())) {
                                                        continue;
                                                }

                                                // Check for @Param annotation on parameter
                                                if (parameter.isAnnotationPresent(Param.class)) {
                                                        Param param = parameter.getAnnotation(Param.class);
                                                        ClaudeAPIParameter claudeAPIParameter = new ClaudeAPIParameter();
                                                        claudeAPIParameter.setName(param.name());
                                                        claudeAPIParameter.setRequired(param.required());
                                                        claudeAPIParameter.setType(JsonTypeConverter.getJsonType(parameter.getType(), null));
                                                        claudeAPIParameter.setDescription(param.description());
                                                        claudeAPI.getParameters().add(claudeAPIParameter);
                                                }
                                        }
                                }

                                // Extract path parameters from URL pattern
                                extractPathParameters(claudeAPI);

                                // Generate request body example if applicable
                                if (hasRequestBody(apiAnnotation)) {
                                        try {
                                                Parameter[] params = method.getParameters();
                                                if (params != null && params.length > 0) {
                                                        // Find the first non-framework parameter as the body parameter
                                                        for (Parameter param : params) {
                                                                if (!isFrameworkParameter(param.getType())) {
                                                                        try {
                                                                                // Use the Object version for proper template rendering
                                                                                Object jsonBody = JsonTypeConverter.classToJsonTypesAsObject(param.getType());
                                                                                claudeAPI.setBody(jsonBody);
                                                                                break;
                                                                        } catch (Exception ex) {
                                                                                // If that fails, skip this parameter
                                                                                logger.debug("Could not generate body for parameter type: " + param.getType().getName(), ex);
                                                                        }
                                                                }
                                                        }
                                                }
                                        } catch (Exception e) {
                                                logger.debug("Could not generate body example for method: " + method.getName(), e);
                                                claudeAPI.setBody(null);
                                        }
                                }

                                // Add to documentation list
                                claudeDocs.add(claudeAPI);
                        }

                        // Sort by path for consistent output
                        claudeDocs.sort(Comparator.comparing(ClaudeAPI::getPath));

                        // Generate documentation using template
                        Map<String, Object> binding = new HashMap<>();
                        binding.put("claudeDocs", claudeDocs);
                        String generatedDocs = buildTemplate(claudeTemplate, binding);
                        replaceTextInFile(claudeOutputPath, startToken, endToken, generatedDocs);

                        logger.info("Successfully generated Claude API documentation for " + claudeDocs.size() + " endpoints");

                } catch (Exception e) {
                        logger.error("Failed to generate enhanced Claude docs: " + e.getMessage(), e);
                }
        }

        /**
         * Check if a parameter type is a framework-specific type that shouldn't be documented
         */
        private boolean isFrameworkParameter(Class<?> type) {
                String typeName = type.getName();
                return typeName.equals("javax.servlet.http.HttpServletRequest") ||
                       typeName.equals("javax.servlet.http.HttpServletResponse") ||
                       typeName.equals("javax.servlet.http.HttpSession") ||
                       typeName.equals("sybrix.easygsp2.UserPrincipal") ||
                       typeName.equals("sybrix.easygsp2.http.Request") ||
                       typeName.equals("sybrix.easygsp2.http.Response") ||
                       typeName.equals("groovy.lang.Binding") ||
                       typeName.equals("org.apache.commons.fileupload.FileItem") ||
                       type.isAssignableFrom(List.class) && typeName.contains("FileItem");
        }

        /**
         * Extract path parameters from URL pattern (e.g., /api/user/{id} or /api/profile/{<\\d+$>profileAdId})
         */
        private void extractPathParameters(ClaudeAPI claudeAPI) {
                String path = claudeAPI.getPath();
                if (path == null) {
                        return;
                }

                // Split multiple paths if comma-separated
                String[] paths = path.split(",");
                Set<String> processedParams = new HashSet<>();

                for (String singlePath : paths) {
                        // Find parameters in the format {paramName} or {<pattern$>paramName}
                        Pattern pattern = Pattern.compile("\\{(<[^>]+>)?([^}]+)\\}");
                        Matcher matcher = pattern.matcher(singlePath.trim());

                        while (matcher.find()) {
                                String paramName = matcher.group(2);

                                // Skip if already processed
                                if (processedParams.contains(paramName)) {
                                        continue;
                                }
                                processedParams.add(paramName);

                                // Check if this parameter is already documented
                                boolean alreadyDocumented = claudeAPI.getParameters().stream()
                                        .anyMatch(p -> p.getName().equals(paramName)) ||
                                        claudeAPI.getQueryStringParameters().stream()
                                        .anyMatch(p -> p.getName().equals(paramName));

                                if (!alreadyDocumented) {
                                        ClaudeAPIParameter pathParam = new ClaudeAPIParameter();
                                        pathParam.setName(paramName);
                                        pathParam.setType("string");
                                        pathParam.setRequired(true);
                                        pathParam.setDescription("");
                                        pathParam.setParameter(paramName);
                                        claudeAPI.getQueryStringParameters().add(pathParam);
                                }
                        }
                }
        }

        /**
         * Check if the API method typically has a request body based on HTTP method
         */
        private boolean hasRequestBody(Api api) {
                if (api.method() == null || api.method().length == 0) {
                        return false;
                }

                for (String method : api.method()) {
                        String upperMethod = method.toUpperCase();
                        if (upperMethod.equals("POST") || upperMethod.equals("PUT") ||
                            upperMethod.equals("PATCH") || upperMethod.equals("DELETE")) {
                                return true;
                        }
                }

                return false;
        }

        private void replaceTextInFile(String filePath,
                                                      String startToken,
                                                      String endToken,
                                                      String newContent) {
                try {
                        Path path = Paths.get(filePath);

                        // Read file content
                        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

                        // Build regex: everything between startToken and endToken (non-greedy)
                        String regex = "(?s)(" + Pattern.quote(startToken) + ")(.*?)(" + Pattern.quote(endToken) + ")";

                        // Replace with preserved tokens
                        // Use Matcher.quoteReplacement to escape any $ or \ in newContent
                        String safeNewContent = java.util.regex.Matcher.quoteReplacement(newContent);
                        String replaced = content.replaceAll(regex, "$1\n" + safeNewContent + "\n$3");

                        // Write back to file
                        Files.write(path, replaced.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
                }catch (IOException e){
                        logger.error("replaceTextInFile failed. filePath: " + filePath + ", startToken: " + startToken + ", endToken: " + endToken + ", newContent: " + newContent + ".  " + e.getMessage(), e);
                }
        }

        private String buildTemplate(String templatePath,Map binding){
                try {
                        SimpleTemplateEngine engine = new groovy.text.SimpleTemplateEngine();
                        Reader r = new InputStreamReader(new FileInputStream(templatePath));

                        Template template = engine.createTemplate(r);

                        return template.make(binding).toString().replaceAll("(?m)^(?:\\s*\\r?\\n){3}", "");//.replaceAll("<br>","");

                }catch (IOException e){

                }
                return "";
        }


        private Api extractClassAnnotation(Annotation[] declaredAnnotations) {

                for (Annotation declaredAnnotation : declaredAnnotations) {
                        if (declaredAnnotation instanceof Api) {
                                return (Api) declaredAnnotation;
                        }
                }

                return _dummy.class.getDeclaredAnnotation(Api.class);
        }


        private Set<String> toList(String[] methods) {
                Set<String> l = new HashSet<String>();
                if (methods != null) {
                        for (String m : methods) {
                                l.add(m);
                        }
                }
                return l;
        }

        private void loadSerializers(PropertiesFile propertiesFile) {
                String jsonSerializer = null;
                String xmlSerializer = null;

                try {
                        jsonSerializer = propertiesFile.getString("json.serializer.class", JSON_SERIALIZER_CLASS);
                        logger.debug("JsonSerializer: " + jsonSerializer);
                        jsonSerializerInstance = (Serializer) Class.forName(jsonSerializer, false, groovyClassLoader).newInstance();
                } catch (Exception e) {
                        if (JSON_SERIALIZER_CLASS.equalsIgnoreCase(jsonSerializer)) {
                                logger.error("Unable to instantiate default json serializer: " + JSON_SERIALIZER_CLASS);
                        } else {
                                logger.warn("error occurred instantiating jsonSerializer: " + jsonSerializer + ", attempting to use default: " + JSON_SERIALIZER_CLASS);

                                try {
                                        jsonSerializerInstance = (Serializer) Class.forName(jsonSerializer, false, groovyClassLoader).newInstance();
                                } catch (Exception e1) {
                                        logger.error("Unable to instantiate default json serializer: " + JSON_SERIALIZER_CLASS);
                                }
                        }
                }

                try {
                        xmlSerializer = propertiesFile.getString("xml.serializer.class", XML_SERIALIZER_CLASS);
                        logger.debug("XmlSerializer: " + xmlSerializer);
                        xmlSerializerInstance = (XmlSerializer) Class.forName(xmlSerializer, false, groovyClassLoader).newInstance();
                } catch (Exception e) {
                        logger.warn("error occurred instantiating xmlSerializer: " + xmlSerializer + ", attempting to use default: " + XML_SERIALIZER_CLASS);

                        try {
                                xmlSerializerInstance = (XmlSerializer) Class.forName(xmlSerializer, false, groovyClassLoader).newInstance();
                        } catch (Exception e1) {
                                logger.error("Unable to instantiate default xml serializer: " + XML_SERIALIZER_CLASS);
                        }
                }
        }

        private Object invokeControllerAction(GroovyObject controller, Method m, Object[] params, sybrix.easygsp2.routing.Route route) throws HttpException, InvokeControllerActionException {
                try {

                        return m.invoke(controller, params);
                } catch (Exception e) {
                        if (e.getCause() instanceof HttpException) {
                                throw (HttpException) e.getCause();

                        } else {
                                if (route.isDuplicate()) {
                                        logger.error("url pattern: " + route.getPath() + " exists on multiple methods.");
                                }
                                logger.error(e.getMessage(), e);
                                throw new InvokeControllerActionException("unable to invoke method " + m.getDeclaringClass().getName() + "." + m.getName() + extractParameterTypes(m.getParameters()) + ",  attempted: " + m.getName() + extractParameterTypes(params), e);
                        }
                }
        }

        public Object populateBean(Object obj, HttpServletRequest request) {

                try {
                        Object value = null;
                        String property = null;

                        BeanInfo sourceInfo = Introspector.getBeanInfo(obj.getClass());
                        PropertyDescriptor[] sourceDescriptors = sourceInfo.getPropertyDescriptors();

                        for (int x = 0; x < sourceDescriptors.length; x++) {
                                try {

                                        if (sourceDescriptors[x].getReadMethod() != null && sourceDescriptors[x].getWriteMethod() != null) {

                                                property = sourceDescriptors[x].getName();
                                                Class params[] = sourceDescriptors[x].getWriteMethod().getParameterTypes();

                                                String val = request.getParameter(property);
                                                if (val != null) {
                                                        value = castToType(val, Class.forName(params[0].getName()));

                                                        if (obj instanceof GroovyObject) {
                                                                MetaClass metaClass = InvokerHelper.getMetaClass(obj);
                                                                metaClass.setProperty(obj, sourceDescriptors[x].getName(), value);
                                                        } else {
                                                                sourceDescriptors[x].getWriteMethod().invoke(obj, new Object[]{value});
                                                        }
                                                }
                                        }
                                } catch (Exception e) {
                                        logger.warn("BeanUtil.populate failed. method=" + property + ", value=" + value, e);
                                }
                        }

                } catch (Exception e) {
                        logger.error("Error occurred populating object from request", e);
                }
                return obj;
        }


        private Object castToType(String valFromRequestParameter, Class clazz) {
                try {
                        if (clazz == Object.class) {
                                return valFromRequestParameter;
                        } else if (clazz.getName().equals("char")) {
                                return new Character(valFromRequestParameter.toCharArray()[0]);
                        } else if (clazz.getName().equals("int")) {
                                return new Integer(valFromRequestParameter);
                        } else if (clazz.getName().equals("long") || clazz.getName().equals("java.lang.Long")) {
                                return new Long(valFromRequestParameter);
                        } else if (clazz.getName().equals("double")) {
                                return new Double(valFromRequestParameter);
                        } else if (clazz.getName().equals("float")) {
                                return new Float(valFromRequestParameter);
                        } else if (clazz.getName().equals("short")) {
                                return new Short(valFromRequestParameter);
                        } else if (clazz.getName().equals("boolean")) {
                                return new Boolean(valFromRequestParameter);
                        }

                        Constructor c = clazz.getConstructor(String.class);
                        return c.newInstance(valFromRequestParameter);

                } catch (NoSuchMethodException e) {
                        e.printStackTrace();
                } catch (InvocationTargetException e) {
                        e.printStackTrace();
                } catch (InstantiationException e) {
                        e.printStackTrace();
                } catch (IllegalAccessException e) {
                        e.printStackTrace();
                }
                return null;
        }

        private String extractParameterTypes(Parameter[] parameters) {
                StringBuffer stringBuffer = new StringBuffer();
                stringBuffer.append("(");
                for (Parameter p : parameters) {
                        stringBuffer.append(p.getType().getName()).append(",");
                }

                if (stringBuffer.length() > 1) {
                        stringBuffer.setLength(stringBuffer.length() - 1);
                }

                stringBuffer.append(")");
                return stringBuffer.toString();
        }

        private String extractParameterTypes(Object[] parameters) {
                StringBuffer stringBuffer = new StringBuffer();
                stringBuffer.append("(");
                for (Object p : parameters) {
                        if (p == null) {
                                stringBuffer.append("null").append(",");
                        } else {
                                stringBuffer.append(p.getClass().getName()).append("=").append(p).append(",");
                        }
                }

                if (stringBuffer.length() > 1) {
                        stringBuffer.setLength(stringBuffer.length() - 1);
                }

                stringBuffer.append(")");
                return stringBuffer.toString();
        }

        private void loadRoutes() throws IOException, URISyntaxException, ClassNotFoundException {

                final Class c = groovyClassLoader.parseClass(new File(this.getClass().getClassLoader().getResource("./routes.groovy").toURI()));

                Closure closure = new Closure(groovyClassLoader) {

                        public Object call() {
                                try {
                                        Binding binding = new Binding();
                                        binding.setVariable("rroutes", routes);
                                        binding.setVariable("groovyClassLoader", groovyClassLoader);

                                        Script routesScript = InvokerHelper.createScript(c, binding);
                                        routesScript.invokeMethod("run", new Object[]{});
                                } catch (Exception e) {
                                        logger.error("error in routes.groovy", e);
                                }

                                return null;
                        }
                };
                GroovyCategorySupport.use(RoutingCategory.class, closure);
        }


        public static sybrix.easygsp2.routing.Route findRoute(String uri, String method) {
                for (sybrix.easygsp2.routing.Route r : routes.values()) {
                        if (r.matches(uri, method)) {
                                logger.debug("matching route found! " + r.toString());
                                return r;
                        }
                }

                logger.debug("no matching route found for " + uri);

                return null;
        }


        private static boolean isJson(String contentType) {

                return contentType == null ? false : contentType.contains(APPLICATION_JSON.getBaseType());
        }

        private static boolean isXML(String contentType) {
                return contentType == null ? false : contentType.contains(APPLICATION_XML.getBaseType());
        }

        private static boolean isFormUrlEncoded(String contentType) {
                return contentType.contains("application/x-www-form-urlencoded");
        }

        private static boolean isMultiPart(String contentType) {
                if (contentType == null)
                        return false;

                return contentType.startsWith("multipart/");
        }

        private String[] extractControllerAndActionName(String contextName, String uri) {
                String[] parts = uri.split("/");
                StringBuffer controller = new StringBuffer();
                String action = "index";

                if (parts.length == 0) {
                        controller.append("Default");
                } else {
                        if (contextName.equals(parts[0])) {
                                controller.append(parts[1]);
                                if (parts.length > 2) {
                                        action = parts[2];
                                }
                        } else {
                                controller.append(parts[0]);
                                if (parts.length > 1) {
                                        action = parts[1];
                                }
                        }
                }
                controller.append("Controller");
                controller.replace(0, 1, String.valueOf(controller.charAt(0)).toUpperCase());

                return new String[]{controller.toString(), action};
        }

        //taken from playframework
        public List<String> lookupParameterNames(Method method, ClassLoader classLoader) {
                try {
                        List<String> parameters = new ArrayList<String>();

                        ClassPool classPool = new ClassPool();
                        classPool.appendSystemPath();
                        classPool.appendClassPath(new LoaderClassPath(this.getClass().getClassLoader()));
                        classPool.appendClassPath("/Users/dsmith/Temp/groovy");

                        CtClass ctClass = classPool.get(method.getDeclaringClass().getName());
                        CtClass[] cc = new CtClass[method.getParameterTypes().length];
                        for (int i = 0; i < method.getParameterTypes().length; i++) {
                                cc[i] = classPool.get(method.getParameterTypes()[i].getName());
                        }
                        CtMethod ctMethod = ctClass.getDeclaredMethod(method.getName(), cc);

                        // Signatures names
                        CodeAttribute codeAttribute = (CodeAttribute) ctMethod.getMethodInfo().getAttribute("Code");
                        if (codeAttribute != null) {
                                LocalVariableAttribute localVariableAttribute = (LocalVariableAttribute) codeAttribute.getAttribute("LocalVariableTable");
                                if (localVariableAttribute != null && localVariableAttribute.tableLength() >= ctMethod.getParameterTypes().length) {
                                        for (int i = 0; i < localVariableAttribute.tableLength(); i++) {
                                                String name = localVariableAttribute.getConstPool().getUtf8Info(localVariableAttribute.nameIndex(i));
                                                if (!name.equals("this") && !parameters.contains(name)) {
                                                        parameters.add(name);
                                                }
                                        }
                                }
                        }

                        return parameters;
                } catch (Exception e) {
                        throw new RuntimeException(e);
                }
        }

        public static File toFile(String path) throws URISyntaxException {
                URL f = Thread.currentThread().getContextClassLoader().getResource(path);
                return new File(f.toURI());
        }

        @Api(url = "", method = "", roles = "")
        private static class _dummy {

        }

        private static MimeType parseMimeType(String s) {
                try {
                        return new MimeType(s);
                } catch (MimeTypeParseException e) {
                        return null;
                }
        }

        public Validator getValidator() {
                return validator;
        }

        public void setValidator(Validator validator) {
                this.validator = validator;
        }

        public PropertiesFile getPropertiesFile() {
                return propertiesFile;
        }

        public EmailService getEmailService() {
                return emailService;
        }

        public AuthenticationService getAuthenticationService() {
                return authenticationService;
        }

        public Map<String, Object> getDynamicProperties() {
                return dynamicProperties;
        }

}
